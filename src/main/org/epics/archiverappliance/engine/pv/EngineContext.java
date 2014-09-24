

/*******************************************************************************
 * Copyright (c) 2011 The Board of Trustees of the Leland Stanford Junior University
 * as Operator of the SLAC National Accelerator Laboratory.
 * Copyright (c) 2011 Brookhaven National Laboratory.
 * EPICS archiver appliance is distributed subject to a Software License Agreement found
 * in file LICENSE that is included with this distribution.
 *******************************************************************************/

package org.epics.archiverappliance.engine.pv;

import gov.aps.jca.Channel;
import gov.aps.jca.Context;

import java.io.IOException;
import java.sql.Timestamp;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map.Entry;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

import org.apache.log4j.Logger;
import org.epics.archiverappliance.StoragePlugin;
import org.epics.archiverappliance.config.ApplianceInfo;
import org.epics.archiverappliance.config.ArchDBRTypes;
import org.epics.archiverappliance.config.ConfigService;
import org.epics.archiverappliance.config.MetaInfo;
import org.epics.archiverappliance.config.PVNames;
import org.epics.archiverappliance.config.PVTypeInfo;
import org.epics.archiverappliance.config.StoragePluginURLParser;
import org.epics.archiverappliance.config.pubsub.PubSubEvent;
import org.epics.archiverappliance.engine.ArchiveEngine;
import org.epics.archiverappliance.engine.metadata.MetaCompletedListener;
import org.epics.archiverappliance.engine.metadata.MetaGet;
import org.epics.archiverappliance.engine.model.ArchiveChannel;
import org.epics.archiverappliance.engine.pv.EPICSV4.ArchiveEngine_EPICSV4;
import org.epics.archiverappliance.engine.writer.WriterRunnable;
import org.epics.archiverappliance.mgmt.policy.PolicyConfig.SamplingMethod;
import org.epics.archiverappliance.utils.ui.GetUrlContent;
import org.epics.archiverappliance.utils.ui.JSONEncoder;
import org.json.simple.JSONObject;
import org.json.simple.JSONValue;

import com.google.common.eventbus.Subscribe;
/***
 * the context for the Archiver Engine
 * @author Luofeng Li
 *
 */
public class EngineContext {
	private static final Logger logger = Logger.getLogger(EngineContext.class.getName());
	private static final Logger configlogger = Logger.getLogger("config." + EngineContext.class.getName());

	private static final double MAXIMUM_DISCONNECTED_CHANNEL_PERCENTAGE_BEFORE_STARTING_METACHANNELS = 5.0;
	private static final int METACHANNELS_TO_START_AT_A_TIME = 10000;

	/** writing thread to write samplebuffer to protocol buffer */
	final private WriterRunnable writer;
    /**is the write thread started or not*/
	private boolean isWriteThreadStarted = false;
	/**the thread pool to schedule all the runnable of the engine*/
	private ScheduledThreadPoolExecutor scheduler = null;
	/**the writing period*/
	private double write_period;
	/**the channel list of channels for  all pvs,but  without the channels created for the meta fields*/
	private final ConcurrentHashMap<String, ArchiveChannel> channelList;
	
    /**the command thread for all  pvs*/
	private JCACommandThread[] command_threads = null;
	private Context[] context2CommandThreadId = null;
	
	/**the total time consumed by the writer*/
	private double totalTimeConsumedByWritter;
	/**the total times of writer executed*/
	private long countOfWrittingByWritter = 0;
	/**the list of pvs controlling other pvs*/
	private ConcurrentHashMap<String, PV> controlingPVList = new ConcurrentHashMap<String, PV>();
	
	private ConfigService configService;
	private String myIdentity;
	
	/** A scheduled thread pool executor only for handling disconnects */
	private ScheduledThreadPoolExecutor disconnectScheduler;

	/**
	 * On disconnects, we add tasks that wait for this timeout to convert reconnects into ca searches into pause resumes.
	 * Ideally, Channel Access is supposed to take care of this but occasionally, we do see connections not reconnecting for a long time.
	 * This tries to address that problem. 
	 */
	private int disconnectCheckTimeoutInMinutes = 20;
	
	/**
	 * The disconnectChecker thread runs in this time frame.
	 * Note this controls both the connect/disconnect checks and the metafields connection initiations.
	 */
	private int disconnectCheckerPeriodInMinutes = 20;
	
	private ScheduledFuture<?> disconnectFuture = null;
	
	private double sampleBufferCapacityAdjustment = 1.0;
	

	/***
	 * 
	 * @return the list of pvs controlling other pvs
	 */
	public ConcurrentHashMap<String, PV> getControlingPVList() {
		return controlingPVList;
	}
   /**
    * set the time consumed by writer to write the sample buffer once
    * @param secondsConsumedByWritter  the time in second consumed by writer to write the sample buffer once
    *  
    */
	public void setSecondsConsumedByWritter(double secondsConsumedByWritter) {
		countOfWrittingByWritter++;
		totalTimeConsumedByWritter = totalTimeConsumedByWritter
				+ secondsConsumedByWritter;
	}
/**
 * 
 * @return the average time in second consumed by writer
 */
	public double getAverageSecondsConsumedByWritter() {
		if (countOfWrittingByWritter == 0)
			return 0;
		return totalTimeConsumedByWritter / (double) countOfWrittingByWritter;
	}
/**
 * This EngineContext should always be singleton
 * @param configService the config service to initialize the engine context
 */
	public EngineContext(final ConfigService configService) {
		String commandThreadCountVarName = "org.epics.archiverappliance.engine.epics.commandThreadCount";
		String commandThreadCountStr = configService.getInstallationProperties().getProperty(commandThreadCountVarName, "10");
		configlogger.info("Creating " + commandThreadCountStr + " command threads as specified by " + commandThreadCountVarName + " in archappl.properties");
		int commandThreadCount = Integer.parseInt(commandThreadCountStr);
		command_threads = new JCACommandThread[commandThreadCount];
		for(int threadNum = 0; threadNum < command_threads.length; threadNum++) { 
			command_threads[threadNum] = new JCACommandThread(configService);
			command_threads[threadNum].start();			
		}
		
		writer = new WriterRunnable(configService);
		channelList = new ConcurrentHashMap<String, ArchiveChannel>();
		logger.debug("Registering EngineContext for events");
		this.configService = configService;
		this.myIdentity = configService.getMyApplianceInfo().getIdentity();
		this.configService.getEventBus().register(this);

		configService.addShutdownHook(new Runnable() {

			@Override
			public void run() {

				logger.info("the archive engine will shutdown");
				try {

					if (scheduler != null) {
						scheduler.shutdown();
					}
					
					Iterator<Entry<String, ArchiveChannel>> itChannel = channelList.entrySet().iterator();
					while (itChannel.hasNext()) {
						Entry<String, ArchiveChannel> channelentry = (Entry<String, ArchiveChannel>) itChannel.next();
						ArchiveChannel channeltemp = channelentry.getValue();
						channeltemp.shutdownMetaChannels();
						channeltemp.stop();
					}
					
					writer.flushBuffer();
					channelList.clear();
					
					// stop the controlling pv

					for (String pvName : controlingPVList.keySet()) {
						controlingPVList.get(pvName).stop();
					}

					controlingPVList.clear();

					scheduler = null;
					isWriteThreadStarted = false;
					for(int threadNum = 0; threadNum < command_threads.length; threadNum++) { 
						command_threads[threadNum].shutdown();
					}

				} catch (Exception e) {
					logger.error(
							"Exception when execuing ShutdownHook inconfigservice",
							e);
				}

				logger.info("the archive engine has been shutdown");

			}

		});
		
		if(configService.getInstallationProperties() != null) { 
			try {
				String disConnStr = configService.getInstallationProperties().getProperty("org.epics.archiverappliance.engine.util.EngineContext.disconnectCheckTimeoutInMinutes", "10");
				if(disConnStr != null) { 
					this.disconnectCheckTimeoutInMinutes = Integer.parseInt(disConnStr);
					logger.debug("Setting disconnectCheckTimeoutInMinutes to " + this.disconnectCheckTimeoutInMinutes);
				}
			} catch(Throwable t) { 
				logger.error("Exception initializing disconnectCheckTimeoutInMinutes", t);
			}
		}
		
		startupDisconnectPauseResumeMonitor(configService);
		
		boolean allContextsHaveBeenInitialized = false;
		for(int loopcount = 0; loopcount < 60 && !allContextsHaveBeenInitialized; loopcount++) {
			allContextsHaveBeenInitialized = true;
			for(int threadNum = 0; threadNum < command_threads.length; threadNum++) {
				Context context = this.command_threads[threadNum].getContext();
				if(context == null) {
					try {
						logger.debug("Waiting for all contexts to be initialized " + threadNum);
						allContextsHaveBeenInitialized = false;
						Thread.sleep(1000);
						break;
					} catch(Exception ex) { 
						// Ifnore
					}
				}
			}
		}

		context2CommandThreadId = new Context[command_threads.length];
		for(int threadNum = 0; threadNum < command_threads.length; threadNum++) {
			Context context = this.command_threads[threadNum].getContext();
			if(context == null) { 
				// We should have had enough time for all the contexts to have initialized...
				logger.error("JCA Context not initialized for thread" + threadNum + ". If you see this, we should a sleep() ahead of this message.");
			} else { 
				this.context2CommandThreadId[threadNum] = context;
			}
		}
		
		
		this.sampleBufferCapacityAdjustment = Double.parseDouble(configService.getInstallationProperties().getProperty("org.epics.archiverappliance.config.PVTypeInfo.sampleBufferCapacityAdjustment", "1.0"));
		logger.debug("Buffer capacity adjustment is " + this.sampleBufferCapacityAdjustment);
	}

	/**
	 * We start at task that runs periodically and checks for CA connectivity. 
	 * If something has not connected in a while, we pause/resume in an attempt to clean any stale state in CAJ/JCA.
	 * @param configService
	 */
	private void startupDisconnectPauseResumeMonitor(final ConfigService configService) {
		disconnectScheduler = new ScheduledThreadPoolExecutor(1, new ThreadFactory() {
			@Override
			public Thread newThread(Runnable r) {
				Thread ret = new Thread(r, "Scheduler for handling PV disconnects.");
				return ret;
			}
		});

		configService.addShutdownHook(new Runnable() {
			@Override
			public void run() {
				logger.info("Shutting down the disconnect scheduler.");
				disconnectScheduler.shutdownNow();
			}
		});

		// Add an assertion in case we accidentally set this to 0 from the props file.
		assert(disconnectCheckerPeriodInMinutes > 0);
		disconnectFuture = disconnectScheduler.scheduleAtFixedRate(new DisconnectChecker(configService), disconnectCheckerPeriodInMinutes, disconnectCheckerPeriodInMinutes, TimeUnit.MINUTES);
	}
	
	public JCACommandThread getJCACommandThread(int jcaCommandThreadId) {
		return this.command_threads[jcaCommandThreadId];
	}


	/**
	 * Use this to assign JCA command threads to PV's
	 * @param pvName
	 * @param iocHostName - Note this can and will often be null.
	 * @return
	 */
	public int assignJCACommandThread(String pvName, String iocHostName) { 
		String pvNameOnly = pvName.split("\\.")[0];
		ArchiveChannel channel = this.channelList.get(pvNameOnly);
		if(channel != null) {
			// Note this is expected for metachannels but not for main channels.
			if(pvName.equals(pvNameOnly)) { 
				logger.debug("We seem to have a channel already for " + pvName + ". Returning its JCA Command thread id.");
			}
			return channel.getJCACommandThreadID();			
		}
		int threadId =  Math.abs(pvNameOnly.hashCode()) % command_threads.length;
		return threadId;
	}
	
	
	public boolean doesContextMatchThread(Context context, int jcaCommandThreadId) { 
		Context contextForThreadId = this.context2CommandThreadId[jcaCommandThreadId];
		if(contextForThreadId != null) { 
			return contextForThreadId == context;
		} else { 
			logger.error("Null context for thread id " + jcaCommandThreadId);
			// We should never get here; but in the case we do failing in this assertion is less harmful than spewing the message with logs...
			return true;
		}
	}
	
/**
 * 
 * @return the channel list of pvs, without the pvs for meta fields
 */
	public ConcurrentHashMap<String, ArchiveChannel> getChannelList() {
		return channelList;
	}
/***
 * set the scheduler for the whole engine
 * @param newscheduler the  ScheduledThreadPoolExecutor for the engine
 */
	public void setScheduler(ScheduledThreadPoolExecutor newscheduler) {
		if (scheduler == null)
			scheduler = newscheduler;
		else {
			logger.error("scheduler has been initialized and you cannot initialize it again!");
		}
	}

/**
 * 
 * @return  the scheduler for the whole engine
 */
	public ScheduledThreadPoolExecutor getScheduler() {
		if (scheduler == null)
			scheduler = (ScheduledThreadPoolExecutor) Executors
					.newScheduledThreadPool(1, new ThreadFactory() {
                        @Override
                        public Thread newThread(Runnable r) {
                                Thread ret = new Thread(r, "Scheduler for the whole engine");
                                return ret;
                        }
					}
							);
		return scheduler;

	}
/**
 * 
 * @return the WriterRunnable for the engines
 */
	public WriterRunnable getWriteThead() {
		return writer;

	}

/**
 * start the write thread of the engine and this is actually called by the first pv when creating channel
 * @param configservice  configservice used by this writer
 */ 
	public void startWriteThread(ConfigService configservice) {
		int defaultWritePeriod = PVTypeInfo.getSecondsToBuffer(configservice);
		double actualWrite_period=writer.setWritingPeriod(defaultWritePeriod);
		this.write_period = actualWrite_period;
		if (scheduler == null) { 
			scheduler = (ScheduledThreadPoolExecutor) Executors.newScheduledThreadPool(1);
		}
		scheduler.scheduleAtFixedRate(writer, 0, (long) (this.write_period * 1000), TimeUnit.MILLISECONDS);
		isWriteThreadStarted = true;
	}
/**
 * 
 * @return the writing period in second
 */
	public double getWritePeriod() {
		return write_period;
	}
/**
 * 
 * @return the status of the writing thread. return true, if it is started.Otherwise, return false;
 */
	public boolean isWriteThreadStarted() {

		return isWriteThreadStarted;
	}
	
	@Subscribe public void computeMetaInfo(PubSubEvent pubSubEvent) {
		if(pubSubEvent.getDestination().equals("ALL") || pubSubEvent.getDestination().equals(myIdentity)) {
			if(pubSubEvent.getType().equals("ComputeMetaInfo")) {
				String pvName = pubSubEvent.getPvName();
				try { 
					logger.debug("ComputeMetaInfo called for " + pvName);
					String fieldName = PVNames.getFieldName(pvName);
					String[] extraFields = configService.getExtraFields();
					if(fieldName != null && !fieldName.equals("")) {
						logger.debug("We are not asking for extra fields for a field value " + fieldName + " for pv " + pvName);
						extraFields = new String[0];
					}
					ArchiveEngine.getArchiveInfo(pvName, configService, extraFields, new ArchivePVMetaCompletedListener(pvName, configService, myIdentity));
					PubSubEvent confirmationEvent = new PubSubEvent("MetaInfoRequested", pubSubEvent.getSource(), pvName);
					configService.getEventBus().post(confirmationEvent);
				} catch(Exception ex) {
					logger.error("Exception requesting metainfo for pv " + pvName, ex);
				}
			} else if(pubSubEvent.getType().equals("StartArchivingPV")) {
				String pvName = pubSubEvent.getPvName();
				try { 
					this.startArchivingPV(pvName);
					PubSubEvent confirmationEvent = new PubSubEvent("StartedArchivingPV", pubSubEvent.getSource(), pvName);
					configService.getEventBus().post(confirmationEvent);
				} catch(Exception ex) {
					logger.error("Exception beginnning archiving pv " + pvName, ex);
				}
			}
		} else {
			logger.debug("Skipping processing event meant for " + pubSubEvent.getDestination());
		}
		
	}
	
	/**
	 * A class that loops thru the archive channels and converts those that have not connected for some time into a pause/resume
	 * Unclear if this is really necessary any more. 
	 * @author mshankar
	 *
	 */
	private final class DisconnectChecker implements Runnable {
		private final ConfigService configService;

		private DisconnectChecker(ConfigService configService) {
			this.configService = configService;
		}

		@Override
		public void run() {
			try { 
				// We run thru all the channels - if a channel has not reconnected in disconnectCheckTimeoutInMinutes, we pause and resume the channel.
				if(EngineContext.this.configService.isShuttingDown()) {
					logger.debug("Skipping checking for disconnected channels as the system is shutting down.");
					return;
				}
				logger.debug("Checking for disconnected channels.");
				LinkedList<String> disconnectedPVNames = new LinkedList<String>();
				LinkedList<String> needToStartMetaChannelPVNames = new LinkedList<String>();
				int totalChannels = EngineContext.this.channelList.size();
				long disconnectTimeoutInSeconds = EngineContext.this.disconnectCheckTimeoutInMinutes*60;
				for(ArchiveChannel channel : EngineContext.this.channelList.values()) {
					if(!channel.isConnected()) {
						logger.debug(channel.getName() + " is not connected. See if we have requested for it some time back and have still not connected.");
						if(disconnectTimeoutInSeconds > 0 && channel.getSecondsElapsedSinceSearchRequest() > disconnectTimeoutInSeconds) { 
							disconnectedPVNames.add(channel.getName());
						} else {
							if(disconnectTimeoutInSeconds > 0) { 
								logger.debug(channel.getName() + " is not connected but we still have some time to go before attempting pause/resume " + channel.getSecondsElapsedSinceSearchRequest() + " and disconnectTimeoutInSeconds " + disconnectTimeoutInSeconds);
							} else { 
								logger.debug("The pause/resume on disconnect has been turned off. Not attempting reconnect using pause/resume for PV " + channel.getName());
							}
						}
					} else { 
						// Channel is connected.
						logger.debug(channel.getName() + " is connected. Seeing if we need to start up the meta channels for the fields.");
						if(channel.metaChannelsNeedStartingUp()) { 
							needToStartMetaChannelPVNames.add(channel.getName());
						}
					}
				}

				int disconnectedChannels = disconnectedPVNames.size();
				
				logger.debug("Checking for disconnected channels yields " + disconnectedChannels + " channels and " + needToStartMetaChannelPVNames.size() + " meta");
				
				if(disconnectedChannels > 0) { 
					for(String disconnectedPV : disconnectedPVNames) { 
						PVTypeInfo typeInfo = EngineContext.this.configService.getTypeInfoForPV(disconnectedPV);
						if(typeInfo != null && !typeInfo.isPaused()) {
							logger.warn("Pausing and resuming  the PV " + disconnectedPV);
							try { 
								ArchiveEngine.pauseArchivingPV(disconnectedPV, configService);
								Thread.sleep(1000);
								List<CommandThreadChannel> channels = EngineContext.this.getAllChannelsForPV(disconnectedPV);
								if(!channels.isEmpty()) {
									logger.warn("Even after pausing, we still seem to have some CAJ channels hanging around for " + disconnectedPV);
								}
								ArchiveEngine.resumeArchivingPV(disconnectedPV, configService);
								logger.debug("Successfully paused and resumed the PV " + disconnectedPV);
							} catch(Throwable t) { 
								logger.error("Exception pausing and resuming PV on disconnect " + disconnectedPV, t);
							}
						} else { 
							logger.debug("Not pausing and resuming already paused/deleted PV " + disconnectedPV);
						}
					}
				}

				// Need to start up the metachannels here after we determine that the cluster has started up..
				// To do this we update the connected/disconnected count for this appliance.
				// We fire up the metachannels gradually only after the entire cluster's connected PV count has reached a certain threshold.
				// First we see if the percentage of disconnected channels in this appliance is lower than a threshold
				if(!needToStartMetaChannelPVNames.isEmpty()) {   
					if ((disconnectedChannels*100.0)/totalChannels < MAXIMUM_DISCONNECTED_CHANNEL_PERCENTAGE_BEFORE_STARTING_METACHANNELS) {
						boolean kickOffMetaChannels = true;
						// Then we repeat the same check for the other appliances in this cluster
						for(ApplianceInfo applianceInfo : configService.getAppliancesInCluster()) { 
							if(applianceInfo.getIdentity().equals(configService.getMyApplianceInfo().getIdentity())) { 
								// We do not check for ourself...
							} else { 
								String connectedPVCountURL = applianceInfo.getEngineURL() + "/ConnectedPVCountForAppliance";
								try { 
									JSONObject connectedPVCount = GetUrlContent.getURLContentAsJSONObject(connectedPVCountURL);
									int applianceTotalPVCount = Integer.parseInt((String) connectedPVCount.get("total"));
									int applianceDisconnectedPVCount = Integer.parseInt((String) connectedPVCount.get("disconnected"));
									if ((applianceDisconnectedPVCount*100.0/applianceTotalPVCount) < MAXIMUM_DISCONNECTED_CHANNEL_PERCENTAGE_BEFORE_STARTING_METACHANNELS) { 
										logger.debug("Appliance " + applianceInfo.getIdentity() + " has connected to most of its channels");
									} else { 
										logger.info("Appliance " + applianceInfo.getIdentity() + " has not connected to most of its channels. Skipping starting of meta channels");
										kickOffMetaChannels = false;
										break;
									}
								} catch(Exception ex) { 
									logger.error("Exception checking for disconnected PVs on appliance " + applianceInfo.getIdentity() + " using URL " + connectedPVCountURL, ex);
								}
							}
						}

						if(kickOffMetaChannels && !needToStartMetaChannelPVNames.isEmpty()) { 
							// We can kick off the metachannels. We kick them off a few at a time.
							for (int i = 0; i < METACHANNELS_TO_START_AT_A_TIME; i++) { 
								String channelPVNameToKickOffMetaFields = needToStartMetaChannelPVNames.poll();
								if(channelPVNameToKickOffMetaFields != null) { 
									logger.debug("Starting meta channels for " + channelPVNameToKickOffMetaFields);
									ArchiveChannel channelToKickOffMetaFields = EngineContext.this.channelList.get(channelPVNameToKickOffMetaFields);
									channelToKickOffMetaFields.startUpMetaChannels();
								} else { 
									logger.debug("No more metachannels to start");
									break;
								}
							}
						}
					}
				}
			} catch(Throwable t) { 
				logger.error("Exception doing the pause/resume checks", t);
			}
		}
	}

	static class ArchivePVMetaCompletedListener implements MetaCompletedListener {
		String pvName;
		ConfigService configService;
		String myIdentity;
		ArchivePVMetaCompletedListener(String pvName, ConfigService configService, String myIdentity) {
			this.pvName = pvName;
			this.configService = configService;
			this.myIdentity = myIdentity;
		}
		
		
		@Override
		public void completed(MetaInfo metaInfo) {
			try { 
				logger.debug("Completed computing archive info for pv " + pvName);
				PubSubEvent confirmationEvent = new PubSubEvent("MetaInfoFinished", myIdentity, pvName);
				JSONEncoder<MetaInfo> encoder = JSONEncoder.getEncoder(MetaInfo.class);
				JSONObject metaInfoObj = encoder.encode(metaInfo);
				confirmationEvent.setEventData(JSONValue.toJSONString(metaInfoObj));
				configService.getEventBus().post(confirmationEvent);
			} catch(Exception ex) {
				logger.error("Exception sending across metainfo for pv " + pvName, ex);
			}
		}
	}
	
	
	private void startArchivingPV(String pvName) throws Exception {
		PVTypeInfo typeInfo = configService.getTypeInfoForPV(pvName);
		if(typeInfo == null) {
			logger.error("Unable to find pvTypeInfo for PV" + pvName + ". This is an error; this method should be called after the pvTypeInfo has been determined and settled in the DHT");
			throw new IOException("Unable to find pvTypeInfo for PV" + pvName);
		}

		ArchDBRTypes dbrType = typeInfo.getDBRType();
		// The first data store in the policy is always the first destination; hence thePolicy.getDataStores()[0]
		StoragePlugin firstDest = StoragePluginURLParser.parseStoragePlugin(typeInfo.getDataStores()[0], configService);
		SamplingMethod samplingMethod = typeInfo.getSamplingMethod();
		float samplingPeriod = typeInfo.getSamplingPeriod();
		int secondsToBuffer = PVTypeInfo.getSecondsToBuffer(configService);
		Timestamp lastKnownTimeStamp = typeInfo.determineLastKnownEventFromStores(configService);
		String controllingPV = typeInfo.getControllingPV();
		String[] archiveFields = typeInfo.getArchiveFields();
		
		logger.info("Archiving PV " + pvName + "using " + samplingMethod.toString() + " with a sampling period of "+ samplingPeriod + "(s)");
		if(!dbrType.isV3Type()) {
			ArchiveEngine_EPICSV4.archivePV(pvName, samplingPeriod, samplingMethod, secondsToBuffer, firstDest, configService, dbrType);
		} else {
			ArchiveEngine.archivePV(pvName, samplingPeriod, samplingMethod, secondsToBuffer, firstDest, configService, dbrType, lastKnownTimeStamp, controllingPV, archiveFields, typeInfo.getHostName()); 
		}
	}
	
	
	public boolean abortComputeMetaInfo(String pvName) { 
		return MetaGet.abortMetaGet(pvName);
	}

	/**
	 * @param newDisconnectCheckTimeoutMins
	 * This is to be used only for unit testing purposes...
	 * There are no guarantees that using this on a running server will be benign.
	 */
	public void setDisconnectCheckTimeoutInMinutesForTestingPurposesOnly(int newDisconnectCheckTimeoutMins) { 
		logger.error("Changing the disconnect timer - this should be done only in the unit tests.");
		disconnectFuture.cancel(false);
		this.disconnectCheckTimeoutInMinutes = newDisconnectCheckTimeoutMins;
		this.disconnectCheckerPeriodInMinutes = newDisconnectCheckTimeoutMins;
		this.startupDisconnectPauseResumeMonitor(configService);
	}
	
	
	/**
	 * Go thru all the contexts and return channels whose names match this
	 * This is to be used for for testing purposes only.
	 * This may not work in running servers; so, please avoid use outside unit tests.
	 * @return
	 */
	public class CommandThreadChannel { 
		JCACommandThread commandThread;
		Channel channel;
		public CommandThreadChannel(JCACommandThread commandThread, Channel channel) {
			this.commandThread = commandThread;
			this.channel = channel;
		}
		public JCACommandThread getCommandThread() {
			return commandThread;
		}
		public Channel getChannel() {
			return channel;
		}
	}
	
	
	public List<CommandThreadChannel> getAllChannelsForPV(String pvName) {
		LinkedList<CommandThreadChannel> retval = new LinkedList<CommandThreadChannel>();
		String pvNameOnly = pvName.split("\\.")[0];
		for(JCACommandThread command_thread : this.command_threads) { 
			Context context = command_thread.getContext();
			for(Channel channel : context.getChannels()) { 
				String channelNameOnly = channel.getName().split("\\.")[0];
				if(channelNameOnly.equals(pvNameOnly)) { 
					retval.add(new CommandThreadChannel(command_thread, channel));
				}
			}
		}
		return retval;
	}

	/**
	 * Per FRIB/PSI, we have a configuration knob to increase/decrease the sample buffer size used by the engine for all PV's.
	 * This comes from archappl.properties and is a double - by default 1.0 which means we leave the buffer size computation as is.
	 * If you want to increase buffer size globally to 150% of what is normally computed, set this to 1.5  
	 * @return
	 */
	public double getSampleBufferCapacityAdjustment() {
		return sampleBufferCapacityAdjustment;
	}
}