/**
* Licensed to the Apache Software Foundation (ASF) under one
* or more contributor license agreements.  See the NOTICE file
* distributed with this work for additional information
* regarding copyright ownership.  The ASF licenses this file
* to you under the Apache License, Version 2.0 (the
* "License"); you may not use this file except in compliance
* with the License.  You may obtain a copy of the License at
*
*     http://www.apache.org/licenses/LICENSE-2.0
*
* Unless required by applicable law or agreed to in writing, software
* distributed under the License is distributed on an "AS IS" BASIS,
* WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
* See the License for the specific language governing permissions and
* limitations under the License.
*/

package org.apache.hadoop.mapreduce.v2.app.speculate;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hdfs.protocol.DatanodeID;
import org.apache.hadoop.hdfs.protocol.DatanodeInfo;
import org.apache.hadoop.mapred.MapTaskAttemptImpl;
import org.apache.hadoop.mapreduce.MRJobConfig;
import org.apache.hadoop.mapreduce.TypeConverter;
import org.apache.hadoop.mapreduce.task.reduce.FetchRateReport;
import org.apache.hadoop.mapreduce.task.reduce.PBSEReduceMessage;
import org.apache.hadoop.mapreduce.task.reduce.PBSEShuffleMessage;
import org.apache.hadoop.mapreduce.task.reduce.PipelineWriteRateReport;
import org.apache.hadoop.mapreduce.task.reduce.ShuffleData;
import org.apache.hadoop.mapreduce.v2.api.records.JobId;
import org.apache.hadoop.mapreduce.v2.api.records.TaskAttemptId;
import org.apache.hadoop.mapreduce.v2.api.records.TaskAttemptState;
import org.apache.hadoop.mapreduce.v2.api.records.TaskId;
import org.apache.hadoop.mapreduce.v2.api.records.TaskType;
import org.apache.hadoop.mapreduce.v2.app.AppContext;
import org.apache.hadoop.mapreduce.v2.app.job.Job;
import org.apache.hadoop.mapreduce.v2.app.job.Task;
import org.apache.hadoop.mapreduce.v2.app.job.TaskAttempt;
import org.apache.hadoop.mapreduce.v2.app.job.event.TaskAttemptStatusUpdateEvent.TaskAttemptStatus;
import org.apache.hadoop.mapreduce.v2.app.job.event.TaskEvent;
import org.apache.hadoop.mapreduce.v2.app.job.event.TaskEventType;
import org.apache.hadoop.service.AbstractService;
import org.apache.hadoop.yarn.event.EventHandler;
import org.apache.hadoop.yarn.exceptions.YarnRuntimeException;
import org.apache.hadoop.yarn.util.Clock;

import com.google.common.annotations.VisibleForTesting;

public class PBSESpeculator extends AbstractService implements Speculator {

  private static final long ON_SCHEDULE = Long.MIN_VALUE;
  private static final long ALREADY_SPECULATING = Long.MIN_VALUE + 1;
  private static final long TOO_NEW = Long.MIN_VALUE + 2;
  private static final long PROGRESS_IS_GOOD = Long.MIN_VALUE + 3;
  private static final long NOT_RUNNING = Long.MIN_VALUE + 4;
  private static final long TOO_LATE_TO_SPECULATE = Long.MIN_VALUE + 5;

  private long soonestRetryAfterNoSpeculate;
  private long soonestRetryAfterSpeculate;
  private double proportionRunningTasksSpeculatable;
  private double proportionTotalTasksSpeculatable;
  private int  minimumAllowedSpeculativeTasks;

  private static final Log LOG = LogFactory.getLog(PBSESpeculator.class);

  private final ConcurrentMap<TaskId, Boolean> runningTasks
      = new ConcurrentHashMap<TaskId, Boolean>();

  // Used to track any TaskAttempts that aren't heart-beating for a while, so
  // that we can aggressively speculate instead of waiting for task-timeout.
  private final ConcurrentMap<TaskAttemptId, TaskAttemptHistoryStatistics>
      runningTaskAttemptStatistics = new ConcurrentHashMap<TaskAttemptId,
          TaskAttemptHistoryStatistics>();
  // Regular heartbeat from tasks is every 3 secs. So if we don't get a
  // heartbeat in 9 secs (3 heartbeats), we simulate a heartbeat with no change
  // in progress.
  private static final long MAX_WAITTING_TIME_FOR_HEARTBEAT = 9 * 1000;

  // These are the current needs, not the initial needs.  For each job, these
  //  record the number of attempts that exist and that are actively
  //  waiting for a container [as opposed to running or finished]
  private final ConcurrentMap<JobId, AtomicInteger> mapContainerNeeds
      = new ConcurrentHashMap<JobId, AtomicInteger>();
  private final ConcurrentMap<JobId, AtomicInteger> reduceContainerNeeds
      = new ConcurrentHashMap<JobId, AtomicInteger>();

  private final Set<TaskId> mayHaveSpeculated = new HashSet<TaskId>();

  private final Configuration conf;
  private AppContext context;
  private Thread speculationBackgroundThread = null;
  private volatile boolean stopped = false;
  private BlockingQueue<SpeculatorEvent> eventQueue
      = new LinkedBlockingQueue<SpeculatorEvent>();
  private TaskRuntimeEstimator estimator;

  private BlockingQueue<Object> scanControl = new LinkedBlockingQueue<Object>();

  private final Clock clock;

  private final EventHandler<TaskEvent> eventHandler;


  // huanke
  private boolean reduceIntersectionSpeculationEnabled = false;
  // huanke just launch one reduce back up task! using counter1
  private int counter1 = 0;
  private DatanodeInfo ignoreNode;
  private List<ArrayList<DatanodeInfo>> lists = new ArrayList<ArrayList<DatanodeInfo>>();
  private Map<TaskId, List<DatanodeInfo>> TaskAndPipeline = new ConcurrentHashMap<>();
  private Set<TaskId> TaskSet = new HashSet<>();

  // @Cesar: To hold the info about the reported fetch rates
  private ShuffleTable shuffleTable = new ShuffleTable();
  private boolean fetchRateSpeculationEnabled = false;
  private boolean smartFetchRateSpeculationEnabled = false;
  private double smartFetchRateSpeculationFactor = 0.0;
  private double fetchRateSpeculationSlowNodeThresshold = 0.0;
  private double fetchRateSpeculationSlowProgressThresshold = 0.0;
  // @Cesar: I will keep events in here
  private Map<ShuffleHost, List<ShuffleRateInfo>> fetchRateUpdateEvents = new HashMap<>();
  // @Cesar: Related to pipe rate reports
  private PipelineTable pipeTable = new PipelineTable();
  private boolean hdfsWriteSpeculationEnabled = false;
  private double hdfsWriteSlowNodeThresshold = 0.0;
  // @Cesar: To detect cases when there is only one reduce task
  private int numReduceTasks = -1;
  private AtomicBoolean singleReduceHostDetected = new AtomicBoolean(false); 
  private AtomicReference<String> singleReduceHost = new AtomicReference<>(null);
  private AtomicBoolean singleReduceHostSpeculated = new AtomicBoolean(false);
  private AtomicReference<TaskAttemptId> singleReduceTaskAttempt = new AtomicReference<>(null);
  private boolean enableSingleReduceSpeculation = false;
  
  // @Cesar: Store pipe updates here
  private Map<HdfsWriteHost, PipelineWriteRateReport> pipeRateUpdateEvents = new HashMap<>();
  // riza: PBSE-Read-2 fields
  private int maxSpeculationDelay = 0;
  private boolean everDelaySpeculation = false;
  private boolean hasDelayThisIteration = false;
  // riza: PBSE-Read-3 fields
  private boolean mapPathSpeculationEnabled = false;
  private double slowTransferRateRatio;
  private double slowTransferRateThreshold;
  AdvanceStatistics globalTransferRate;
  private Map<TaskAttemptId, TaskAttemptStatus> recentAttemptStatus;
  private Map<TaskId, Set<TaskAttemptId>> knownAttempt;
  TaskAttemptId switchingAttempt;

  public PBSESpeculator(Configuration conf, AppContext context) {
    this(conf, context, context.getClock());
  }

  public PBSESpeculator(Configuration conf, AppContext context, Clock clock) {
    this(conf, context, getEstimator(conf, context), clock);
  }

  static private TaskRuntimeEstimator getEstimator
      (Configuration conf, AppContext context) {
    TaskRuntimeEstimator estimator;

    try {
      // "yarn.mapreduce.job.task.runtime.estimator.class"
      Class<? extends TaskRuntimeEstimator> estimatorClass
          = conf.getClass(MRJobConfig.MR_AM_TASK_ESTIMATOR,
                          LegacyTaskRuntimeEstimator.class,
                          TaskRuntimeEstimator.class);

      Constructor<? extends TaskRuntimeEstimator> estimatorConstructor
          = estimatorClass.getConstructor();

      estimator = estimatorConstructor.newInstance();

      estimator.contextualize(conf, context);
    } catch (InstantiationException ex) {
      LOG.error("Can't make a speculation runtime estimator", ex);
      throw new YarnRuntimeException(ex);
    } catch (IllegalAccessException ex) {
      LOG.error("Can't make a speculation runtime estimator", ex);
      throw new YarnRuntimeException(ex);
    } catch (InvocationTargetException ex) {
      LOG.error("Can't make a speculation runtime estimator", ex);
      throw new YarnRuntimeException(ex);
    } catch (NoSuchMethodException ex) {
      LOG.error("Can't make a speculation runtime estimator", ex);
      throw new YarnRuntimeException(ex);
    }

    return estimator;
  }

  // This constructor is designed to be called by other constructors.
  // However, it's public because we do use it in the test cases.
  // Normally we figure out our own estimator.
  public PBSESpeculator
      (Configuration conf, AppContext context, TaskRuntimeEstimator estimator, Clock clock) {
    super(PBSESpeculator.class.getName());

    this.conf = conf;
    this.context = context;
    this.estimator = estimator;
    this.clock = clock;
    this.eventHandler = context.getEventHandler();
    this.soonestRetryAfterNoSpeculate =
        conf.getLong(MRJobConfig.SPECULATIVE_RETRY_AFTER_NO_SPECULATE,
                MRJobConfig.DEFAULT_SPECULATIVE_RETRY_AFTER_NO_SPECULATE);
    this.soonestRetryAfterSpeculate =
        conf.getLong(MRJobConfig.SPECULATIVE_RETRY_AFTER_SPECULATE,
                MRJobConfig.DEFAULT_SPECULATIVE_RETRY_AFTER_SPECULATE);
    this.proportionRunningTasksSpeculatable =
        conf.getDouble(MRJobConfig.SPECULATIVECAP_RUNNING_TASKS,
                MRJobConfig.DEFAULT_SPECULATIVECAP_RUNNING_TASKS);
    this.proportionTotalTasksSpeculatable =
        conf.getDouble(MRJobConfig.SPECULATIVECAP_TOTAL_TASKS,
                MRJobConfig.DEFAULT_SPECULATIVECAP_TOTAL_TASKS);
    this.minimumAllowedSpeculativeTasks =
        conf.getInt(MRJobConfig.SPECULATIVE_MINIMUM_ALLOWED_TASKS,
                MRJobConfig.DEFAULT_SPECULATIVE_MINIMUM_ALLOWED_TASKS);

    // @Cesar: read the properties
    this.fetchRateSpeculationEnabled = conf.getBoolean(
        "mapreduce.experiment.enable_fetch_rate_speculation", false);
    this.fetchRateSpeculationSlowNodeThresshold = conf.getDouble(
        "mapreduce.experiment.fetch_rate_speculation_slow_thresshold",
        Double.MAX_VALUE);
    this.fetchRateSpeculationSlowProgressThresshold = conf.getDouble(
        "mapreduce.experiment.fetch_rate_speculation_progress_thresshold",
        Double.MAX_VALUE);
    this.smartFetchRateSpeculationEnabled = conf.getBoolean(
        "mapreduce.experiment.smart_fetch_rate_speculation_enabled", false);
    this.smartFetchRateSpeculationFactor = conf.getDouble(
        "mapreduce.experiment.smart_fetch_rate_speculation_factor", 3.0);

    // @Cesar: Same for write speculation
    this.hdfsWriteSpeculationEnabled = conf.getBoolean(
    		"mapreduce.experiment.enable_write_rate_speculation", false);
    this.hdfsWriteSlowNodeThresshold = conf.getDouble(
    		"mapreduce.experiment.write_rate_speculation_slow_thresshold", 0.0);
    this.enableSingleReduceSpeculation = conf.getBoolean(
    		"mapreduce.experiment.enable_single_reducer_speculation", false);
    		
    // huanke
    this.reduceIntersectionSpeculationEnabled = conf.getBoolean(
        "pbse.enable.for.reduce.pipeline", false);

    // riza
    this.maxSpeculationDelay =
        conf.getInt(MRJobConfig.PBSE_MAP_DELAY_INTERVAL_MS,
            MRJobConfig.DEFAULT_PBSE_MAP_DELAY_INTERVAL_MS) 
        / (int) this.soonestRetryAfterNoSpeculate;
    this.mapPathSpeculationEnabled =
        conf.getBoolean(MRJobConfig.PBSE_MAP_PATH_SPECULATION_ENABLED,
            MRJobConfig.DEFAULT_PBSE_MAP_PATH_SPECULATION_ENABLED);
    this.slowTransferRateRatio =
        conf.getDouble(MRJobConfig.PBSE_MAP_SLOW_TRANSFER_RATIO,
            MRJobConfig.DEFAULT_PBSE_MAP_SLOW_TRANSFER_RATIO);
    this.slowTransferRateThreshold =
        conf.getDouble(MRJobConfig.PBSE_MAP_SLOW_TRANSFER_FIXED_THRESHOLD,
            MRJobConfig.DEFAULT_PBSE_MAP_SLOW_TRANSFER_FIXED_THRESHOLD);
    this.everDelaySpeculation = false;
    this.globalTransferRate = new AdvanceStatistics();
    this.recentAttemptStatus = new ConcurrentHashMap<TaskAttemptId, TaskAttemptStatus>();
    this.knownAttempt = new HashMap<TaskId, Set<TaskAttemptId>>();
  }

  /* ************************************************************* */

  // This is the task-mongering that creates the two new threads -- one for
  // processing events from the event queue and one for periodically
  // looking for speculation opportunities

  @Override
  protected void serviceStart() throws Exception {
    Runnable speculationBackgroundCore
        = new Runnable() {
      private long nextDefaultSpeculation = 0;
      private long nextFetchRateSpeculation = 0;
      private long nextHdfsWriteRateSpeculation = 0;
      private long nextMapPathSpeculation = 0;

      @Override
      public void run() {
        while (!stopped && !Thread.currentThread().isInterrupted()) {
          hasDelayThisIteration = false;
          long backgroundRunStartTime = clock.getTime();
          try {
            int speculations = 0;
            int reduceSpeculation = 0;
            int mapSpeculation = 0;
            
            long minWait = soonestRetryAfterNoSpeculate;

            // riza: prioritize PBSE speculation

            if (hdfsWriteSpeculationEnabled
            	&& nextHdfsWriteRateSpeculation <= backgroundRunStartTime) {
	          // @Cesar: To speculate reduce tasks based on write transfer rate
	          int spec = checkPipeTable();
	          reduceSpeculation += spec;
	
	          long myWait = (spec > 0 ? soonestRetryAfterSpeculate
	                  : soonestRetryAfterNoSpeculate);
	          nextHdfsWriteRateSpeculation = backgroundRunStartTime + myWait;
	          minWait = Math.min(minWait, myWait);
	        }
            
            if (fetchRateSpeculationEnabled
                && nextFetchRateSpeculation <= backgroundRunStartTime) {
              // riza: this is Cesar's map speculation based on fetch rate
              int spec = checkFetchRateTable();
              mapSpeculation += spec;

              long myWait = soonestRetryAfterNoSpeculate;
              nextFetchRateSpeculation = backgroundRunStartTime + myWait;
              minWait = Math.min(minWait, myWait);
            }

            if (mapPathSpeculationEnabled
                && nextMapPathSpeculation <= backgroundRunStartTime) {
              // riza: map path group speculation
              try {
                //int spec = calculateMapPathSpeculation();
                int spec = agressiveMapPathSpeculation();
                mapSpeculation += spec;

                long myWait = (spec > 0 ? soonestRetryAfterNoSpeculate //soonestRetryAfterSpeculate
                    : soonestRetryAfterNoSpeculate);
                nextMapPathSpeculation = backgroundRunStartTime + myWait;
                minWait = Math.min(minWait, myWait);
              } catch (Exception ex) {
                StringWriter sw = new StringWriter();
                PrintWriter pw = new PrintWriter(sw);
                ex.printStackTrace(pw);
                LOG.error(sw.toString());
              }
            }

            // riza: this is basic speculative algorithm. If PBSE algorithm has
            // kick in before, wait until next iteration to speculate again.
            if (nextDefaultSpeculation <= backgroundRunStartTime) {
              if (mapSpeculation <= 0)
                mapSpeculation += maybeScheduleAMapSpeculation();
              if (reduceSpeculation <= 0)
                reduceSpeculation += maybeScheduleAReduceSpeculation();

              long myWait = (mapSpeculation + reduceSpeculation > 0 ? soonestRetryAfterSpeculate
                  : soonestRetryAfterNoSpeculate);
              nextDefaultSpeculation = backgroundRunStartTime + myWait;
              minWait = Math.min(minWait, myWait);
            }

            speculations = mapSpeculation + reduceSpeculation;
            // riza: disable backoff for now
            long mininumRecomp = Math.min(soonestRetryAfterNoSpeculate, minWait);
            // long mininumRecomp = speculations > 0 ? soonestRetryAfterSpeculate
            //     : soonestRetryAfterNoSpeculate;
            long wait = Math.max(mininumRecomp, clock.getTime()
                - backgroundRunStartTime);

            if (speculations > 0) {
              LOG.info("We launched " + speculations
                  + " speculations.  Sleeping " + wait + " milliseconds.");
            }
            Object pollResult
                = scanControl.poll(wait, TimeUnit.MILLISECONDS);
          } catch (InterruptedException e) {
            if (!stopped) {
              LOG.error("Background thread returning, interrupted", e);
            }
            return;
          }
        }
      }
    };
    speculationBackgroundThread = new Thread
        (speculationBackgroundCore, "PBSESpeculator background processing");
    speculationBackgroundThread.start();
    super.serviceStart();
  }

 @Override
  protected void serviceStop() throws Exception {
    stopped = true;
    // this could be called before background thread is established
    if (speculationBackgroundThread != null) {
      speculationBackgroundThread.interrupt();
    }
    super.serviceStop();
  }

  @Override
  public void handleAttempt(TaskAttemptStatus status) {
    long timestamp = clock.getTime();
    statusUpdate(status, timestamp);
  }

  // This section is not part of the Speculator interface; it's used only for
  // testing
  public boolean eventQueueEmpty() {
    return eventQueue.isEmpty();
  }

  // This interface is intended to be used only for test cases.
  public void scanForSpeculations() {
    LOG.info("We got asked to run a debug speculation scan.");
    // debug
    System.out.println("We got asked to run a debug speculation scan.");
    System.out.println("There are " + scanControl.size()
        + " events stacked already.");
    scanControl.add(new Object());
    Thread.yield();
  }

  /* ************************************************************* */

  // This section contains the code that gets run for a SpeculatorEvent

  private AtomicInteger containerNeed(TaskId taskID) {
    JobId jobID = taskID.getJobId();
    TaskType taskType = taskID.getTaskType();

    ConcurrentMap<JobId, AtomicInteger> relevantMap
        = taskType == TaskType.MAP ? mapContainerNeeds : reduceContainerNeeds;

    AtomicInteger result = relevantMap.get(jobID);

    if (result == null) {
      relevantMap.putIfAbsent(jobID, new AtomicInteger(0));
      result = relevantMap.get(jobID);
    }

    return result;
  }

  private synchronized void processSpeculatorEvent(SpeculatorEvent event) {
    switch (event.getType()) {
    case ATTEMPT_STATUS_UPDATE:
      statusUpdate(event.getReportedStatus(), event.getTimestamp());
      // @Cesar: Is this a success event? is this a map task? is fetch rate spec
      // enabled?
      if (event.isSuccedded()
          && event.getReportedStatus().id.getTaskId().getTaskType() == TaskType.MAP
          && fetchRateSpeculationEnabled) {
        // @Cesar: Report attempt as finished
        shuffleTable.reportSuccessfullAttempt(event.getMapperHost(),
            event.getReportedStatus().id, event.getTime());
        LOG.info("@Cesar: Reported successfull map at " + event.getMapperHost()
            + " : " + event.getReportedStatus().id);
        // @Cesar: The event contains the time reported for this map task
      }
      // @Cesar: Same for reduce tasks
      if(event.isSuccedded() 
         && event.getReportedStatus().id.getTaskId().getTaskType() == TaskType.REDUCE 
    	 && hdfsWriteSpeculationEnabled){
    	 // @Cesar: Mark as finished
    	 this.pipeTable.markAsFinished(event.getReportedStatus().id.getTaskId());
    	 LOG.info("@Cesar: Reported successfull reduce at " + event.getMapperHost()
         	+ " : " + event.getReportedStatus().id);
      }
      break;

    case TASK_CONTAINER_NEED_UPDATE:
    {
      AtomicInteger need = containerNeed(event.getTaskID());
      need.addAndGet(event.containersNeededChange());
      break;
    }

    case ATTEMPT_START:
    {
      LOG.info("ATTEMPT_START " + event.getTaskID());
      estimator.enrollAttempt(event.getReportedStatus(), event.getTimestamp());

      // @Cesar: Enroll also in here
      if (event.getTaskID().getTaskType() == TaskType.MAP
          && fetchRateSpeculationEnabled) {
        // @Cesar: An attempt started for a given map task
        shuffleTable
            .reportStartedTask(event.getMapperHost(), event.getTaskID());
        LOG.info("@Cesar: Map task reported at node " + event.getMapperHost()
            + " : " + event.getTaskID());
      }
      // @Cesar: Get number of reduce tasks
      // I need to know the number of reducers in this job
      // (not the current one, the total one). 
      if(numReduceTasks < 0){
    	  // @Cesar: Just set once, i will remove this
    	  // when i find a cleaner way
    	  Job job = context.getJob(event.getTaskID().getJobId());
    	  if(job != null){
    		  numReduceTasks = job.getTotalReduces();
    	  }
      }
      // @Cesar: Is this a reduce task?
      if(event.getTaskID().getTaskType() == TaskType.REDUCE
    	&& hdfsWriteSpeculationEnabled){
    	  if(numReduceTasks == 1 
    		 && singleReduceHostDetected.get() == false
    		 && event.getMapperHost() != null
    		 && event.getReportedStatus().id != null){
    		  // @Cesar: Done, now let the checkWriteDiversityFunction do its thing
    		  singleReduceHostDetected.set(true);
    		  singleReduceHost.set(event.getMapperHost());
    		  singleReduceTaskAttempt.set(event.getReportedStatus().id);
    		  LOG.info("@Cesar: Detected single reduce host!");
    	  }
    	  else{
//    		  LOG.info("@Cesar: No single reducer detected: " + numReduceTasks + ", " + singleReduceHostDetected.get() +
//    				  ", " + event.getMapperHost() + ", " + event.getReportedStatus().id);
    	  }
      }
      break;
    }

    case JOB_CREATE:
    {
      LOG.info("JOB_CREATE " + event.getJobID());
      estimator.contextualize(getConfig(), context);
      break;
    }

    // @Cesar: Handle fetch rate update
    case ATTEMPT_FETCH_RATE_UPDATE:
    {
      processFetchRateUpdate(event);
      break;
    }

    // @Cesar: Handle pipe rate update
    case ATTEMPT_PIPE_RATE_UPDATE:
    {
      processPipeRateUpdate(event);
      break;
    }
    
    case ATTEMPT_PIPELINE_UPDATE:
    {
      processPipelineUpdate(event.getReportedStatus(), event.getDNpath());
      break;
    }
    
    case ATTEMPT_SWITCH_DATANODE:
    {
      synchronized (recentAttemptStatus) {
        switchingAttempt = event.getReportedStatus().id;
        recentAttemptStatus.remove(switchingAttempt);
      }
      LOG.info("riza: will delay speculation on " + switchingAttempt
          + " until next status update");
      break;
    }
    }
  }

  /**
   * Absorbs one TaskAttemptStatus
   *
   * @param reportedStatus the status report that we got from a task attempt
   *        that we want to fold into the speculation data for this job
   * @param timestamp the time this status corresponds to.  This matters
   *        because statuses contain progress.
   */
  protected void statusUpdate(TaskAttemptStatus reportedStatus, long timestamp) {

    String stateString = reportedStatus.taskState.toString();

    TaskAttemptId attemptID = reportedStatus.id;
    TaskId taskID = attemptID.getTaskId();
    Job job = context.getJob(taskID.getJobId());

    if (job == null) {
      return;
    }

    Task task = job.getTask(taskID);

    if (task == null) {
      return;
    }

    estimator.updateAttempt(reportedStatus, timestamp);

    if (stateString.equals(TaskAttemptState.RUNNING.name())) {
      runningTasks.putIfAbsent(taskID, Boolean.TRUE);
    } else {
      runningTasks.remove(taskID, Boolean.TRUE);
      if (!stateString.equals(TaskAttemptState.STARTING.name())) {
        runningTaskAttemptStatistics.remove(attemptID);
      }
    }
    
    // riza: keep all recent task attempt status
    if (reportedStatus.progress >= 1.0){
      reportedStatus.taskState = TaskAttemptState.SUCCEEDED;
      reportedStatus.stateString = TaskAttemptState.SUCCEEDED.name();
    }
    recentAttemptStatus.put(attemptID, reportedStatus);
    globalTransferRate.add(attemptID, reportedStatus.mapTransferRate);
    registerAttempt(attemptID);
    
    if ((switchingAttempt != null) && attemptID.equals(switchingAttempt)) {
      // the switching attempt just sent new update. Retract the switching status.
      switchingAttempt = null;
    }
  }

  /*   *************************************************************    */

//This is the code section that runs periodically and adds speculations for
// those jobs that need them.


  // This can return a few magic values for tasks that shouldn't speculate:
  //  returns ON_SCHEDULE if thresholdRuntime(taskID) says that we should not
  //     considering speculating this task
  //  returns ALREADY_SPECULATING if that is true.  This has priority.
  //  returns TOO_NEW if our companion task hasn't gotten any information
  //  returns PROGRESS_IS_GOOD if the task is sailing through
  //  returns NOT_RUNNING if the task is not running
  //
  // All of these values are negative.  Any value that should be allowed to
  //  speculate is 0 or positive.
  private long speculationValue(TaskId taskID, long now, boolean onlyCheckNegativeResult) {
    Job job = context.getJob(taskID.getJobId());
    Task task = job.getTask(taskID);
    Map<TaskAttemptId, TaskAttempt> attempts = task.getAttempts();
    long acceptableRuntime = Long.MIN_VALUE;
    long result = Long.MIN_VALUE;

    if (!mayHaveSpeculated.contains(taskID)) {
      acceptableRuntime = estimator.thresholdRuntime(taskID);
      if (acceptableRuntime == Long.MAX_VALUE) {
        return ON_SCHEDULE;
      }
    }

    // @Cesar: Ignore tasks relaunched by slow shuffle
    synchronized (shuffleTable) {
      if (shuffleTable.wasSpeculated(taskID)) {
        // @Cesar: Return in here, this wont be speculated again
//        LOG.info("@Cesar: Task " + taskID + " wont be speculated again.");
        return ON_SCHEDULE;
      }
    }

    TaskAttemptId runningTaskAttemptID = null;

    int numberRunningAttempts = 0;

    for (TaskAttempt taskAttempt : attempts.values()) {

      if (taskAttempt.getState() == TaskAttemptState.RUNNING
          || taskAttempt.getState() == TaskAttemptState.STARTING) {
        if (++numberRunningAttempts > 1) {
          return ALREADY_SPECULATING;
        }
        runningTaskAttemptID = taskAttempt.getID();

        long estimatedRunTime = estimator.estimatedRuntime(runningTaskAttemptID);

        long taskAttemptStartTime = estimator
            .attemptEnrolledTime(runningTaskAttemptID);
        if (taskAttemptStartTime > now) {
          // This background process ran before we could process the task
          // attempt status change that chronicles the attempt start
          return TOO_NEW;
        }

        // riza: do not speculate task that has not sent its first status update
        MapTaskAttemptImpl mapTaskAttempt = (taskAttempt instanceof MapTaskAttemptImpl) ? (MapTaskAttemptImpl) taskAttempt
            : null;
        if (mapTaskAttempt != null) {
          if (maxSpeculationDelay > 0
              && DatanodeID.nullDatanodeID.equals(mapTaskAttempt
                  .getLastDatanodeID())) {
            if (!everDelaySpeculation)
              LOG.info("PBSE-Read-2: " + runningTaskAttemptID
                  + " speculation delayed");
            everDelaySpeculation = true;

            if (!hasDelayThisIteration) {
              maxSpeculationDelay--;
              hasDelayThisIteration = true;
            }

            LOG.info(runningTaskAttemptID
                + " has not report its datanode, speculator return TOO_NEW, "
                + maxSpeculationDelay + " speculation delay left");
            return TOO_NEW;
          }
          
          if ((switchingAttempt != null) && runningTaskAttemptID.equals(switchingAttempt)) {
            LOG.info(runningTaskAttemptID
                + " just switch its datanode, speculator return TOO_NEW, "
                + maxSpeculationDelay + " speculation delay left");
            return TOO_NEW;
          }
        }
        
        if (!onlyCheckNegativeResult) {
          long estimatedEndTime = estimatedRunTime + taskAttemptStartTime;
  
          long estimatedReplacementEndTime
              = now + estimator.estimatedNewAttemptRuntime(taskID);
  
          float progress = taskAttempt.getProgress();
          TaskAttemptHistoryStatistics data =
              runningTaskAttemptStatistics.get(runningTaskAttemptID);
          if (data == null) {
            runningTaskAttemptStatistics.put(runningTaskAttemptID,
                new TaskAttemptHistoryStatistics(estimatedRunTime, progress, now));
          } else {
            if (estimatedRunTime == data.getEstimatedRunTime()
                && progress == data.getProgress()) {
              // Previous stats are same as same stats
              if (data.notHeartbeatedInAWhile(now)) {
                // Stats have stagnated for a while, simulate heart-beat.
                TaskAttemptStatus taskAttemptStatus = new TaskAttemptStatus();
                taskAttemptStatus.id = runningTaskAttemptID;
                taskAttemptStatus.progress = progress;
                taskAttemptStatus.taskState = taskAttempt.getState();
                // Now simulate the heart-beat
                handleAttempt(taskAttemptStatus);
              }
            } else {
              // Stats have changed - update our data structure
              data.setEstimatedRunTime(estimatedRunTime);
              data.setProgress(progress);
              data.resetHeartBeatTime(now);
            }
          }
  
          if (estimatedEndTime < now) {
            return PROGRESS_IS_GOOD;
          }
  
          if (estimatedReplacementEndTime >= estimatedEndTime) {
            return TOO_LATE_TO_SPECULATE;
          }
  
          result = estimatedEndTime - estimatedReplacementEndTime;
        } else {
          result = 0;
        }
      }
    }

    // If we are here, there's at most one task attempt.
    if (numberRunningAttempts == 0) {
      return NOT_RUNNING;
    }

    if (acceptableRuntime == Long.MIN_VALUE) {
      acceptableRuntime = estimator.thresholdRuntime(taskID);
      if (acceptableRuntime == Long.MAX_VALUE) {
        return ON_SCHEDULE;
      }
    }

    return result;
  }

  // Add attempt to a given Task.
  protected void addSpeculativeAttempt(TaskId taskID) {
    // @Cesar: Do not re espculate if this have been launched by slow shuffle
    LOG.info("PBSESpeculator.addSpeculativeAttempt -- we are speculating " + taskID);
    eventHandler.handle(new TaskEvent(taskID, TaskEventType.T_ADD_SPEC_ATTEMPT));
    mayHaveSpeculated.add(taskID);
  }

  @Override
  public void handle(SpeculatorEvent event) {
    processSpeculatorEvent(event);
  }

  private int maybeScheduleAMapSpeculation() {
    return maybeScheduleASpeculation(TaskType.MAP);
  }

  private int maybeScheduleAReduceSpeculation() {
    return maybeScheduleASpeculation(TaskType.REDUCE);
  }

  private int maybeScheduleASpeculation(TaskType type) {
    int successes = 0;

    long now = clock.getTime();

    ConcurrentMap<JobId, AtomicInteger> containerNeeds
        = type == TaskType.MAP ? mapContainerNeeds : reduceContainerNeeds;

    for (ConcurrentMap.Entry<JobId, AtomicInteger> jobEntry : containerNeeds.entrySet()) {
      // This race conditon is okay.  If we skip a speculation attempt we
      //  should have tried because the event that lowers the number of
      //  containers needed to zero hasn't come through, it will next time.
      // Also, if we miss the fact that the number of containers needed was
      //  zero but increased due to a failure it's not too bad to launch one
      //  container prematurely.
      if (jobEntry.getValue().get() > 0) {
        continue;
      }

      int numberSpeculationsAlready = 0;
      int numberRunningTasks = 0;

      // loop through the tasks of the kind
      Job job = context.getJob(jobEntry.getKey());

      Map<TaskId, Task> tasks = job.getTasks(type);

      int numberAllowedSpeculativeTasks
          = (int) Math.max(minimumAllowedSpeculativeTasks,
              proportionTotalTasksSpeculatable * tasks.size());

      TaskId bestTaskID = null;
      long bestSpeculationValue = -1L;

      // this loop is potentially pricey.
      // TODO track the tasks that are potentially worth looking at
      for (Map.Entry<TaskId, Task> taskEntry : tasks.entrySet()) {
        long mySpeculationValue = speculationValue(taskEntry.getKey(), now, false);

        if (mySpeculationValue == ALREADY_SPECULATING) {
          ++numberSpeculationsAlready;
        }

        if (mySpeculationValue != NOT_RUNNING) {
          ++numberRunningTasks;
        }

        if (mySpeculationValue > bestSpeculationValue) {
          bestTaskID = taskEntry.getKey();
          bestSpeculationValue = mySpeculationValue;
        }
      }
      numberAllowedSpeculativeTasks
          = (int) Math.max(numberAllowedSpeculativeTasks,
              proportionRunningTasksSpeculatable * numberRunningTasks);

      // If we found a speculation target, fire it off
      if (bestTaskID != null
          && numberAllowedSpeculativeTasks > numberSpeculationsAlready) {
        addSpeculativeAttempt(bestTaskID);
        ++successes;
      }
    }

    return successes;
  }

  private int computeSpeculations() {
    // We'll try to issue one map and one reduce speculation per job per run
    return maybeScheduleAMapSpeculation() + maybeScheduleAReduceSpeculation();
  }

  static class TaskAttemptHistoryStatistics {

    private long estimatedRunTime;
    private float progress;
    private long lastHeartBeatTime;

    public TaskAttemptHistoryStatistics(long estimatedRunTime, float progress,
        long nonProgressStartTime) {
      this.estimatedRunTime = estimatedRunTime;
      this.progress = progress;
      resetHeartBeatTime(nonProgressStartTime);
    }

    public long getEstimatedRunTime() {
      return this.estimatedRunTime;
    }

    public float getProgress() {
      return this.progress;
    }

    public void setEstimatedRunTime(long estimatedRunTime) {
      this.estimatedRunTime = estimatedRunTime;
    }

    public void setProgress(float progress) {
      this.progress = progress;
    }

    public boolean notHeartbeatedInAWhile(long now) {
      if (now - lastHeartBeatTime <= MAX_WAITTING_TIME_FOR_HEARTBEAT) {
        return false;
      } else {
        resetHeartBeatTime(now);
        return true;
      }
    }

    public void resetHeartBeatTime(long lastHeartBeatTime) {
      this.lastHeartBeatTime = lastHeartBeatTime;
    }
  }

  @VisibleForTesting
  public long getSoonestRetryAfterNoSpeculate() {
    return soonestRetryAfterNoSpeculate;
  }

  @VisibleForTesting
  public long getSoonestRetryAfterSpeculate() {
    return soonestRetryAfterSpeculate;
  }

  @VisibleForTesting
  public double getProportionRunningTasksSpeculatable() {
    return proportionRunningTasksSpeculatable;
  }

  @VisibleForTesting
  public double getProportionTotalTasksSpeculatable() {
    return proportionTotalTasksSpeculatable;
  }

  @VisibleForTesting
  public int getMinimumAllowedSpeculativeTasks() {
    return minimumAllowedSpeculativeTasks;
  }

  //@Cesar: In here, we check for write diversity.
  // The idea is that if the first node in the pipeline
  // for all the running reduce tasks is the same, then
  // we will speculate one of them. Which one? the first...
  // This function will just check if there is intersection
  // on the current reports, i will ask later who is running and who is 
  // not
  public boolean checkWriteDiversity(PipelineTable pipelineTable){
	  Map<HdfsWriteHost, PipelineWriteRateReport> allReports = 
			  pipelineTable.getReports();
	  String reducePipelineFirstNode = null;
	  Map<Integer, String> ignorePipe = null;
	  String ignoreHost = null;
	  TaskAttemptId attemptId = null;
	  if(allReports.size() == 0 && numReduceTasks > 1){
		  // @Cesar: No reports, no write diversity needed
		  return false;
	  }
	  else if(singleReduceHostDetected.get() == true 
			  && singleReduceHostSpeculated.get() == false
			  && enableSingleReduceSpeculation){
		  // @Cesar: Only one report, is this the only reduce task
		  // on the job? If so, speculate it  
		  if(numReduceTasks == 1 && singleReduceHostSpeculated.get() == false){
			  // @Cesar: Get the first report
			  if(allReports.size() > 0){
				  Entry<HdfsWriteHost, PipelineWriteRateReport> report =
						  allReports.entrySet().iterator().next();
				  ignoreHost = report.getKey().getReduceHost();
				  ignorePipe = report.getValue().getPipeOrderedNodes();
				  attemptId = report.getKey().getReduceTaskAttempt();
			  }
			  else{
				  ignoreHost = singleReduceHost.get();
				  ignorePipe = null;
				  attemptId = singleReduceTaskAttempt.get();
			  }
			  
			  List<String> firstInPipe = new ArrayList<String>();
			  if(ignorePipe != null) {
				  firstInPipe.add(ignorePipe.get(0));
			  }
			  else{
				  firstInPipe.add(ignoreHost);
			  }
			  // @Cesar: Speculate
			  speculateReduceTaskDueToWriteDiversity(
					  attemptId, 
					  firstInPipe, 
					  ignoreHost);
			  pipelineTable.setWriteDiversityKickedIn(true);
			  LOG.info("@Cesar: Write diversity kicked in: only one reduce task in job");
			  // @Cesar: almost done
			  if(singleReduceHostDetected.get() == true){
				  singleReduceHostSpeculated.set(true);
			  }
			  return true;
		  }
	  }
	  else{
		  // @Cesar: Check for diversity, is the first node
		  // common is all reduce tasks? 
		  int commonValueCount = 0;
		  int finishedCount = 0;
		  for(Entry<HdfsWriteHost, PipelineWriteRateReport> report :
			  allReports.entrySet()){
			  	if(!pipelineTable.isFinished(report.getKey()
			  			.getReduceTaskAttempt().getTaskId())){
					if(reducePipelineFirstNode == null){
						// @Cesar: First report
						reducePipelineFirstNode = report.getValue()
													.getPipeOrderedNodes().get(0);
					}
					else if(report.getValue()
								.getPipeOrderedNodes().get(0)
								.compareToIgnoreCase(reducePipelineFirstNode) == 0){
						// @Cesar: Intersection on first node
						if(commonValueCount == 0){
							ignoreHost = report.getKey().getReduceHost();
							ignorePipe = report.getValue().getPipeOrderedNodes();
							attemptId = report.getKey().getReduceTaskAttempt();
						}
						++commonValueCount;
					}
			  	}
			  	else{
			  		++finishedCount;
			  	}
		  }
		  // @Cesar: Is there a common value?
		  if(commonValueCount == (allReports.size() - finishedCount) && commonValueCount > 0){
			  // @Cesar: speculate one reduce task. Which one?
			  // One that is not finished. So, the first one analyzed
			  List<String> firstInPipe = new ArrayList<String>();
			  firstInPipe.add(ignorePipe.get(0));
			  speculateReduceTaskDueToWriteDiversity(
					  attemptId, 
					  firstInPipe, 
					  ignoreHost);
			  // @Cesar: Write diversity kicked in so we are fine
			  pipelineTable.setWriteDiversityKickedIn(true);
			  LOG.info("@Cesar: Write diversity kicked in: all running have common first node");
			  return true;
		  }
	  }
	  // @Cesar: Ready, return
//	  LOG.info("@Cesar: No write diversity needed, the reports shows that is not necessary");
	  return false;
  }
  
  //@Cesar: Check this table to speculate slow write reducer
  private int checkPipeTable() {
  try {
      // @Cesar: Start by pulling all reports
      synchronized (pipeRateUpdateEvents){
        pipeTable.storeFromMap(pipeRateUpdateEvents);
        pipeRateUpdateEvents.clear();
      }
      // @Cesar: Lets first apply diversity check...
      if(!pipeTable.isWriteDiversityKickedIn()){
    	  // @Cesar: Only if write div has not kicked in
    	  // already
    	  boolean spec = checkWriteDiversity(pipeTable);
    	  if(spec) return 1;
      }
//      LOG.info("@Cesar: Starting pipe check");
      // @Cesar: Num of task speculated
      int numSpecs = 0;
      // @Cesar: Now process all entries
      SimpleSlowHdfsWriteEstimator estimator = new SimpleSlowHdfsWriteEstimator();
      Map<HdfsWriteHost, PipelineWriteRateReport> reports = pipeTable.getReports();
      List<HdfsWriteHost> markedForDelete = new ArrayList<>();
//      LOG.info("@Cesar: We have " + reports.size() + " to check");
      for(Entry<HdfsWriteHost, PipelineWriteRateReport> report : reports.entrySet()){
//    	  LOG.info("@Cesar: Analizing " + report.getKey() + " with " + report.getValue());
    	  // @Cesar: Analyze all this reports
    	  if(!pipeTable.isFinished(report.getKey().getReduceTaskAttempt().getTaskId())){
    		  // @Cesar: Is slow?
    		  if(estimator.isSlow(
    				  report.getKey(), 
    				  report.getValue(), 
    				  hdfsWriteSlowNodeThresshold)){
    			  // @Cesar: Count and comply with min nuber of reports sent
    			  if(pipeTable.canSpeculate(report.getKey(), PipelineTable.MIN_REPORT_COUNT) 
    				 && !pipeTable.wasSpeculated(report.getKey().getReduceTaskAttempt().getTaskId())){
    				  // @Cesar: I will add an spec attempt for this task, the idea is that it
        			  // avoids the same pipeline
        			  ++numSpecs;
        			  // @Cesar: get pipe nodes in order
        			  speculateReduceTaskDueToSlowWrite(
        					  report.getKey().getReduceTaskAttempt(), 
        					  new ArrayList<String>(
        							  report.getValue().getPipeOrderedNodes().values()), 
        					  report.getKey().getReduceHost());
        			  // @Cesar: Banned
        			  pipeTable.bannReporter(report.getKey());
        			  // @Cesar: Im also going to delete this one, dont want it anymore
        			  markedForDelete.add(report.getKey());
        			  // @Cesar: Mark as speculated
        			  pipeTable.markAsSpeculated(report.getKey().getReduceTaskAttempt().getTaskId());
    			  }
    			  else{
//    				  LOG.info("@Cesar: The pipe is slow, but we have not received enough reports yet or"
//    				  		+ " we are trying to speculate something that should not be...");
    			  }
    			  
    		  }
    	  }
    	  else{
//			  LOG.info("@Cesar: Task " + report.getKey().getReduceTaskAttempt().getTaskId() + 
//			  			 " is already finished, we wont analyze it");
			  // @Cesar: Also clean...
			  markedForDelete.add(report.getKey());
			  
    	  }
      }
      // @Cesar: clean a little
      pipeTable.cleanReports(markedForDelete);
      return numSpecs;
      
    } catch (Exception exc) {
      LOG.error("@Cesar: Catching dangerous exception on checkPipeTable: " + exc.getMessage()
          + " : " + exc.getCause());
      // @Cesar: Print exc
      try{
	      StringWriter errors = new StringWriter();
	      exc.printStackTrace(new PrintWriter(errors));
	      LOG.error(errors.toString());
	      errors.close();
      }
      catch(Exception e){
    	  // @Cesar: Nothing to do, dont care
      }
      return 0;
    }
  }
  
  // riza: wrap Huan's algorithm
  private int computeReduceIntersectionSpeculation() {
    DatanodeInfo ignoreNode = checkIntersection();
    if (ignoreNode != null) {
      LOG.info("@huanke checkIntersection returns ignoreNode :" + ignoreNode);
      LOG.info("PBSE-Write-Diversity-1 taskId " + TaskAndPipeline.keySet()
          + " choose-datanode " + TaskAndPipeline + " IntersectedNode "
          + ignoreNode);
      relauchReduceTask(ignoreNode);
      return 1;
    }
    return 0;
  }
  
  // huanke
  private DatanodeInfo checkIntersection() {
    try {
      TaskType type = TaskType.REDUCE;
      if (TaskSet.size() != 0) {
        Iterator<TaskId> iter = TaskSet.iterator();
        TaskId Ttmp = iter.next();

        Job job = context.getJob(Ttmp.getJobId());
        Map<TaskId, Task> reduceTasks = job.getTasks(type);

        for (Map.Entry<TaskId, Task> taskEntry : reduceTasks.entrySet()) {
          if (TaskAndPipeline != null
              && TaskAndPipeline.size() == reduceTasks.size()) {
            List<DatanodeInfo> pipeline = TaskAndPipeline.get(taskEntry
                .getKey());
            if (pipeline != null) {
              lists.add((ArrayList<DatanodeInfo>) pipeline);
            }
          }
        }
      }

      if (lists.isEmpty()) {
        return null;
      } else {
//        LOG.info("@huanke lists1: " + lists);
        List<DatanodeInfo> commonNodes = new ArrayList<DatanodeInfo>();
        commonNodes.addAll(lists.get(0));
        for (ListIterator<ArrayList<DatanodeInfo>> iter = lists.listIterator(); iter
            .hasNext();) {
          ArrayList<DatanodeInfo> myPipeline = iter.next();
          commonNodes.retainAll(myPipeline);
        }
        if (commonNodes.isEmpty()) {
          return null;
        } else {
          LOG.info("@huanke got common nodes over reducers " + commonNodes);
          return commonNodes.get(0);
        }
      }
    } catch (Exception exc) {
      LOG.error("@huanke: There was an exception here, we dont let this thread die for this: "
          + exc.getMessage());
      return null;
    }
  }

  // huanke
  private void relauchReduceTask(DatanodeInfo ignoreNode) {
    TaskId taskID = TaskAndPipeline.keySet().iterator().next();
    LOG.info("@huanke relauchReduceTask" + taskID);
    eventHandler.handle(new TaskEvent(taskID, TaskEventType.T_ADD_SPEC_ATTEMPT,
        ignoreNode));
    mayHaveSpeculated.add(taskID);
  }

  // @Cesar
  private void speculateReduceTaskDueToSlowWrite(TaskAttemptId attempt, 
		  										 List<String> badPipe, 
		  										 String badHost) {
   LOG.info("@Cesar speculateReduceTaskDueToSlowWrite " + attempt.getTaskId());
   LOG.info(PBSEReduceMessage.createPBSEMessageReduceTaskSpeculated(
		   		badHost, 
		   		attempt.toString(), 
		   		badPipe));
   eventHandler.handle(new TaskEvent(attempt.getTaskId(), TaskEventType.T_ADD_SPEC_ATTEMPT,
		   							 badPipe, badHost, false));
   mayHaveSpeculated.add(attempt.getTaskId());
 }
  
  // @Cesar
  private void speculateReduceTaskDueToWriteDiversity(TaskAttemptId attempt, 
		  										 	  List<String> badPipe, 
		  										 	  String badHost) {
   // @Cesar: Double check
   if(!pipeTable.isFinished(attempt.getTaskId())){	  
	   LOG.info("@Cesar speculateReduceTaskDueToWriteDiversity " + attempt.getTaskId());
	   LOG.info(PBSEReduceMessage.createPBSEMessageReduceTaskSpeculatedDueToWriteDiversity(
			   		badHost, 
			   		attempt.toString(), 
			   		badPipe));
	   eventHandler.handle(new TaskEvent(attempt.getTaskId(), TaskEventType.T_ADD_SPEC_ATTEMPT,
			   							 badPipe, badHost, true));
	   mayHaveSpeculated.add(attempt.getTaskId());
   }
   else{
	   // @Cesar: This is a potential problem
	   LOG.error("@Cesar: Trying to speculate a reduce task that is finished while "
	   			+ " checking for diversity!: " + attempt);
   }
 }

  
  // riza: Huan's pipeline update handler
  private void processPipelineUpdate(TaskAttemptStatus reportedStatus,
      ArrayList<DatanodeInfo> dNpath) {
    String stateString = reportedStatus.taskState.toString();

//    LOG.info("@huanke at the beginning" + TaskAndPipeline + " size: "
//        + TaskAndPipeline.size() + dNpath + reportedStatus.Pipeline);

    TaskAttemptId attemptID = reportedStatus.id;
    TaskId taskID = attemptID.getTaskId();
    Job job = context.getJob(taskID.getJobId());

    synchronized (TaskAndPipeline) {
      if (taskID.getTaskType() == TaskType.REDUCE) {
        if (dNpath.size() == 0) {
//          LOG.info("@huanke TaskAndPipeline is empty at this moment");
        } else {
          if (TaskSet.add(taskID)) {
            TaskAndPipeline.put(taskID, dNpath);
          }
        }
      }
    }
//    LOG.info("@huanke PBSESpeculator TaskAndPipeline" + TaskAndPipeline
//        + TaskAndPipeline.size());
  }

  // huanke reduce task does not launch backup task as map task like T_ADD_SPEC_ATTEMPT
  protected void relaunchTask(TaskId taskID) {
    LOG.info("PBSESpeculator.@huanke-relaunchTask -- we are speculating a reduce task of id "
        + taskID);
    eventHandler.handle(new TaskEvent(taskID, TaskEventType.T_ATTEMPT_KILLED));
    // @huanke: Add this as speculated
    mayHaveSpeculated.add(taskID);
  }

  // riza: Cesar's fetch rate update handler
  private void processFetchRateUpdate(SpeculatorEvent event) {
    FetchRateReport report = event.getReport();
    String reduceHost = ShuffleTable.parseHost(event.getReducerNode());
    if (reduceHost != null && fetchRateSpeculationEnabled) {
      // @Cesar: If we are here, report is not null
      for (Entry<String, ShuffleData> reportEntry : report
          .getFetchRateReport().entrySet()) {
        // @Cesar: This is the report for one mapper
        String mapHost = ShuffleTable.parseHost(reportEntry.getKey());
        ShuffleRateInfo info = new ShuffleRateInfo();
        info.setFetchRate(reportEntry.getValue().getTransferRate());
        info.setMapHost(mapHost);
        info.setMapTaskAttempId(TypeConverter.toYarn(reportEntry.getValue()
            .getMapTaskId()));
        info.setReduceHost(reduceHost);
        info.setReduceTaskAttempId(event.getReduceTaskId());
        info.setTransferProgress(reportEntry.getValue().getBytes()
            / (reportEntry.getValue().getTotalBytes() != 0 ? reportEntry
                .getValue().getTotalBytes() : 1.0));
        info.setTotalBytes(reportEntry.getValue().getTotalBytes());
        info.setShuffledBytes(reportEntry.getValue().getBytes());
        info.setUnit(reportEntry.getValue().getTransferRateUnit().toString());
        // @Cesar: Lets save this report in queue
        synchronized (fetchRateUpdateEvents) {
          if (fetchRateUpdateEvents.containsKey(new ShuffleHost(mapHost))) {
            fetchRateUpdateEvents.get(new ShuffleHost(mapHost)).add(info);
          } else {
            List<ShuffleRateInfo> rates = new ArrayList<>();
            rates.add(info);
            fetchRateUpdateEvents.put(new ShuffleHost(mapHost), rates);
          }

        }
        // @Cesar: Done
//        LOG.info("@Cesar: Stored report with " + info);
      }
    } else {
      // @Cesar: Small error, just log it. It should never happen
      LOG.error("@Cesar: Trying to update fetch rate report with null reducer. Data is "
          + report);
    }
    // @Cesar: Log that this event happened
    LOG.info("ATTEMPT_FETCH_RATE_UPDATE " + event.getReduceTaskId());
  }

  //riza: Cesar's pipe rate update handler
  private void processPipeRateUpdate(SpeculatorEvent event) {
   PipelineWriteRateReport report = event.getPipelineWriteRateReport();
   String reduceHost = event.getReducerNode();
   TaskAttemptId reduceTaskAttempt = event.getReduceTaskId();
   if (reduceHost != null && reduceTaskAttempt != null) {
	   // @Cesar: So, lets store this report
	   synchronized(pipeRateUpdateEvents){
		   pipeRateUpdateEvents.put(new HdfsWriteHost(reduceHost, reduceTaskAttempt), report);
//		   LOG.info("@Cesar: Report stored for " + reduceHost + 
//				   " and " + reduceTaskAttempt + " : " + report);
	   }
   }
   else{
	   // @Cesar: Log this error to see if happens
	   LOG.error("@Cesar: Pipe report contained null field? This cannot happen");
   }
   // @Cesar: Log that this event happened
   LOG.info("ATTEMPT_PIPE_RATE_UPDATE " + event.getReduceTaskId());
  }
  
  // @Cesar: Check the table, return num speculations
  private int checkFetchRateTable() {
    try {
      // @Cesar: Start by pulling all reports
      synchronized (fetchRateUpdateEvents) {
        for (Entry<ShuffleHost, List<ShuffleRateInfo>> events : fetchRateUpdateEvents
            .entrySet()) {
          for (ShuffleRateInfo rate : events.getValue()) {
            shuffleTable.reportRate(events.getKey(), rate);
          }
        }
        fetchRateUpdateEvents.clear();
      }
      // @Cesar: This will be the return value
      int numSpeculatedMapTasks = 0;
      // @Cesar: This is the class that selects wich nodes
      // are slow
      HarmonicAverageSlowShuffleEstimator estimator = new HarmonicAverageSlowShuffleEstimator();
      // @Cesar: In here, we have to check the shuffle rate for
      // one mapper, and choose if we are going to speculate
      // or not
      // @Cesar: So, iterate the fetch rate table
//      LOG.info("@Cesar: Starting fetch rate speculation check");
      Map<ShuffleHost, Set<ShuffleRateInfo>> allReports = shuffleTable
          .getReports();
      // @Cesar: Done released object, now go
      // Lets iterate
      if (allReports != null) {
        // @Cesar: Mark this hosts to be checked to delete if no entries
//        LOG.info("@Cesar: We have " + allReports.size() + " map hosts to check");
        Iterator<Entry<ShuffleHost, Set<ShuffleRateInfo>>> fetchRateTableIterator = allReports
            .entrySet().iterator();
        while (fetchRateTableIterator.hasNext()) {
          Entry<ShuffleHost, Set<ShuffleRateInfo>> nextEntry = fetchRateTableIterator
              .next();
          // @Cesar: Do we have enough reports to speculate something??
          if (!shuffleTable.canSpeculate(nextEntry.getKey().getMapHost())) {
            // @Cesar: Continue loop
//            LOG.info("@Cesar: No speculation possible for host "
//                + nextEntry.getKey().getMapHost()
//                + " since it does not have enough reports");
            continue;
          }
          // @Cesar: So, in this row we have one map host.
          // This map host can have multiple map task associated, so if we
          // detect
          // that this node is slow, then we will relaunch all tasks in here
          if (estimator.isSlow(nextEntry.getKey().getMapHost(),
              nextEntry.getValue(), fetchRateSpeculationSlowNodeThresshold,
              fetchRateSpeculationSlowProgressThresshold)) {
            // @Cesar: So, this node is slow. Get all its map tasks
            Set<TaskAttemptId> allMapTasksInHost = null;
            if (smartFetchRateSpeculationEnabled) {
              allMapTasksInHost = shuffleTable
                  .getCompliantSuccessfullMapTaskAttemptsFromHost(nextEntry
                      .getKey().getMapHost(), smartFetchRateSpeculationFactor);
            } else {
              allMapTasksInHost = shuffleTable
                  .getAllSuccessfullMapTaskAttemptsFromHost(nextEntry.getKey()
                      .getMapHost());
            }
            LOG.info("@Cesar: " + allMapTasksInHost.size()
                + " map tasks will be speculated at " + nextEntry.getKey());
            Iterator<TaskAttemptId> mapIterator = allMapTasksInHost.iterator();
            while (mapIterator.hasNext()) {
              TaskAttemptId next = mapIterator.next();
              if (!shuffleTable.wasSpeculated(next.getTaskId())) {
                // @Cesar: Only speculate if i havent done it already
                LOG.info("@Cesar: Relaunching attempt " + next + " of task "
                    + next.getTaskId() + " at host "
                    + nextEntry.getKey().getMapHost());
                relaunchTask(next.getTaskId(), nextEntry.getKey().getMapHost(),
                    next);
                // @Cesar: also, add to the list of tasks that may have been
                // speculated already
                shuffleTable.bannMapTask(next.getTaskId());
                // @Cesar: Mark this attempt as relaunched (killed)
                shuffleTable.unsucceedTaskAtHost(nextEntry.getKey()
                    .getMapHost(), next.getTaskId());
                shuffleTable.bannMapTaskAttempt(next);
                // @Cesar: This is the real number of speculated map tasks
                ++numSpeculatedMapTasks;
              } else {
//                LOG.info("@Cesar: Not going to relaunch " + next
//                    + " since task " + next.getTaskId()
//                    + " was speculated already");
              }
              // @Cesar: Clean host
              shuffleTable.cleanHost(nextEntry.getKey().getMapHost());
            }
          } else {
            LOG.info("Estimator established that "
                + nextEntry.getKey().getMapHost()
                + " is not slow, so no speculations for this host");
          }
        }
      }
//      LOG.info("@Cesar: Finished fetch rate speculation check");
      return numSpeculatedMapTasks;
    } catch (Exception exc) {
      LOG.error("@Cesar: Catching dangerous exception on checkFetchRateTable: " + exc.getMessage()
          + " : " + exc.getCause());
      // @Cesar: Print exc
      try{
	      StringWriter errors = new StringWriter();
	      exc.printStackTrace(new PrintWriter(errors));
	      LOG.error(errors.toString());
	      errors.close();
      }
      catch(Exception e){
    	  // @Cesar: Nothing to do, dont care
      }
      return 0;
    }
  }

  // @Cesar: I will use this to send my map speculative attempt for now
  // It can change if at the end of the day i discover that is the same
  // using addSpeculativeAttempt
  protected void relaunchTask(TaskId taskID, String mapperHost,
      TaskAttemptId mapId) {
    LOG.info("PBSESpeculator.relaunchTask.@cesar -- we are speculating a map task of id "
        + taskID);
    eventHandler.handle(new TaskEvent(taskID, TaskEventType.T_ATTEMPT_KILLED,
        mapperHost, mapId));

    // @Cesar: Log
    LOG.info(PBSEShuffleMessage.createPBSEMessageMapTaskRelaunched(mapperHost));

    // @Cesar: Add this as speculated
    mayHaveSpeculated.add(taskID);
  }
  
  private void registerAttempt(TaskAttemptId attemptId) {
    TaskId taskId = attemptId.getTaskId();
    if (!knownAttempt.containsKey(taskId))
      knownAttempt.put(taskId, new HashSet<TaskAttemptId>());
    knownAttempt.get(taskId).add(attemptId);
  }
  
  private Set<TaskAttemptId> getKnownAttempt(TaskId taskId) {
    return knownAttempt.get(taskId);
  }

  // riza: map path based speculation by grouping
  private int calculateMapPathSpeculation() {
    HashMap<String, DataStatistics> hostStatistics = new HashMap<String, DataStatistics>();

    int successes = 0;

    long now = clock.getTime();

    ConcurrentMap<JobId, AtomicInteger> containerNeeds = mapContainerNeeds;

    for (ConcurrentMap.Entry<JobId, AtomicInteger> jobEntry : containerNeeds.entrySet()) {
      // riza: follow maybeScheduleASpeculation. Skip speculating job that still
      // requesting container
      if (jobEntry.getValue().get() > 0) {
        continue;
      }

      HashMap<String, HashSet<TaskId>> taskGroup = new HashMap<String, HashSet<TaskId>>();

      int numberSpeculationsAlready = 0;
      int numberFinishedAlready = 0;
      int numberUnknownAttempt = 0;

      // loop through the tasks of the kind
      Job job = context.getJob(jobEntry.getKey());

      Map<TaskId, Task> tasks = job.getTasks(TaskType.MAP);

      TaskAttemptStatus slowestMap = null;

      // riza: first pass to build up path statistic, grouping, and find attempt
      // having least transferRate
      for (Map.Entry<TaskId, Task> taskEntry : tasks.entrySet()) {
        TaskId taskId = taskEntry.getKey();
        long mySpeculationValue = speculationValue(taskId, now, true);

        if ((mySpeculationValue == TOO_NEW) || (knownAttempt.get(taskId) == null)) {
          // riza: no know report has arrive from this attempt
          ++numberUnknownAttempt;
          continue;
        }
        
        if (taskEntry.getValue().isFinished()) {
          numberFinishedAlready++;
          continue;
        }

        if (mySpeculationValue == ALREADY_SPECULATING) {
          // riza: should we speculate task that has been speculated already?
          // speculated before
          ++numberSpeculationsAlready;
          continue;
        }

        // riza: Not all map in path statistic still running. If we got to
        // speculate a path, we want to speculate only tasks of that path
        // group that actually still running.
        // If we got to this point, it means the task is still running
        
        TaskAttemptStatus latestMap = null;
        for (TaskAttemptId attemptId : getKnownAttempt(taskId)) {

          TaskAttemptStatus map = recentAttemptStatus.get(attemptId);
          if (map == null) {
            continue;
          }
          if (map.mapTransferRate > 0.0d) {
            
            // riza: We build map path statistic from all transferring
            // MapTaskAttempt that still running, having rate > 0.0 Mbps
      
            // riza: stat for datanode
            if (!hostStatistics.containsKey(map.lastDatanodeID
                .getHostName()))
              hostStatistics.put(map.lastDatanodeID.getHostName(),
                  new DataStatistics());
            DataStatistics dnStat = hostStatistics.get(map.lastDatanodeID.getHostName());
            dnStat.add(map.mapTransferRate);

            // riza: stat for mapnode
            if (!hostStatistics.containsKey(map.containerHost))
              hostStatistics.put(map.containerHost, new DataStatistics());
            DataStatistics hostStat = hostStatistics.get(map.containerHost);
            hostStat.add(map.mapTransferRate);

            if ((latestMap == null) || (map.id.compareTo(latestMap.id) > 0)) {
              latestMap = map;
//              LOG.info("Got latest map " + latestMap.id + ", rate: "
//                  + latestMap.mapTransferRate + ", state: "
//                  + latestMap.taskState);
            }
          }
        }

        if (latestMap != null) {
          if (latestMap.containerHost != latestMap.lastDatanodeID.getHostName()) {
            // riza: We also only interested in speculating non-local task.
  
            // riza: group task based on datanode
            String datanode = latestMap.lastDatanodeID.getHostName();
            if (!taskGroup.containsKey(datanode))
              taskGroup.put(datanode, new HashSet<TaskId>());
            taskGroup.get(datanode).add(taskEntry.getValue().getID());
  
            // riza: group task based on mapnode
            String mapnode = latestMap.containerHost;
            if (!taskGroup.containsKey(mapnode))
              taskGroup.put(mapnode, new HashSet<TaskId>());
            taskGroup.get(mapnode).add(taskEntry.getValue().getID());
          }

          // riza: memorize one slowest map from task that not finished yet. If
          // path group algorithm fail to detect straggling path and this slow map
          // has rate below the threshold, we speculate this single task.

          if ((slowestMap == null)
              || (slowestMap.mapTransferRate > latestMap.mapTransferRate)) {
              slowestMap = latestMap;
            }
        }
      }
      
//      if (slowestMap != null)
//        LOG.info("Current slowest map " + slowestMap.id + " rate: "
//            + slowestMap.mapTransferRate + " state: "
//            + slowestMap.taskState);

      // riza: second pass to find path group having least transfer rate
      double threshold = Math.max(slowTransferRateThreshold,
          globalTransferRate.mean() * slowTransferRateRatio);
      String slowestHost = "";
      double slowestTransferRate = threshold;
      for (Map.Entry<String, DataStatistics> pathGroup : hostStatistics.entrySet()) {
        if ((pathGroup.getKey() != "UNKNOWN")
            && (pathGroup.getValue().mean() <= slowestTransferRate)
            && (taskGroup.containsKey(pathGroup.getKey()))) {
          slowestHost = pathGroup.getKey();
          slowestTransferRate = pathGroup.getValue().mean();
        }
      }

      if (slowestTransferRate < threshold) {
        // riza: path group speculation
        LOG.info("PBSE-Read-3: Speculating "
            + taskGroup.get(slowestHost).size() + " tasks on path group "
            + slowestHost + " having avg rate " + slowestTransferRate
            + " threshold " + threshold
            + " global avg rate " + globalTransferRate);

        for (TaskId taskId : taskGroup.get(slowestHost)) {
          addSpeculativeAttempt(taskId);
          ++successes;
        }
      } else if ((slowestMap != null)
          && (slowestMap.mapTransferRate < threshold)) {
        // riza: individual map speculation
        LOG.info("PBSE-Read-3: Speculating single map " + slowestMap.id
            + " having path (" + slowestMap.lastDatanodeID + ","
            + slowestMap.containerHost + ") avg rate "
            + slowestMap.mapTransferRate + " threshold " + threshold
            + " global rate " + globalTransferRate);

        addSpeculativeAttempt(slowestMap.id.getTaskId());
        ++successes;
      } else {
        LOG.info("Nothing to speculate, global rate: " + globalTransferRate
            + " threshold: " + threshold + " Mbps taskGroup size: "
            + taskGroup.size() + " slowestHost: " + slowestHost
            + " slowestTransferRate: " + slowestTransferRate
            + " already speculated " + numberSpeculationsAlready
            + " already finished " + numberFinishedAlready
            + " unknown attempt " + numberUnknownAttempt
            + " slowestMap rate: "
            + (slowestMap == null ? -1.0 : slowestMap.mapTransferRate));

//        if (hostStatistics.containsKey("pc831.emulab.net")) {
//          DataStatistics ds = hostStatistics.get("pc831.emulab.net");
//          LOG.info("pc831 " + ds + ", and taskGroup: " + (taskGroup.get("pc831.emulab.net")));
//        }
      }
    }

    return successes;
  }

  // riza: immediately speculate any slow paths
  private int agressiveMapPathSpeculation() {
    List<TaskId> toSPeculate = new ArrayList<TaskId>();
    
    double threshold = Math.max(slowTransferRateThreshold,
        globalTransferRate.mean() * slowTransferRateRatio);
    double slowestTransferRate = threshold;
    double fastestRate = 0.0d;

    int successes = 0;

    long now = clock.getTime();

    ConcurrentMap<JobId, AtomicInteger> containerNeeds = mapContainerNeeds;

    for (ConcurrentMap.Entry<JobId, AtomicInteger> jobEntry : containerNeeds.entrySet()) {
      // riza: follow maybeScheduleASpeculation. Skip speculating job that still
      // requesting container
      if (jobEntry.getValue().get() > 0) {
        continue;
      }

      int numberSpeculationsAlready = 0;
      int numberFinishedAlready = 0;
      int numberUnknownAttempt = 0;
      
      // loop through the tasks of the kind
      Job job = context.getJob(jobEntry.getKey());
      
      Map<TaskId, Task> tasks = job.getTasks(TaskType.MAP);

      TaskAttemptStatus slowestMap = null;

      // riza: first pass to build up path statistic, grouping, and find attempt
      // having least transferRate
      for (Map.Entry<TaskId, Task> taskEntry : tasks.entrySet()) {
        TaskId taskId = taskEntry.getKey();
        long mySpeculationValue = speculationValue(taskId, now, true);

        if ((mySpeculationValue == TOO_NEW) || (knownAttempt.get(taskId) == null)) {
          // riza: no know report has arrive from this attempt
          ++numberUnknownAttempt;
          continue;
        }
        
        if (taskEntry.getValue().isFinished()) {
          numberFinishedAlready++;
          continue;
        }

        if (mySpeculationValue == ALREADY_SPECULATING) {
          // riza: should we speculate task that has been speculated already?
          // speculated before
          ++numberSpeculationsAlready;
          continue;
        }

        // riza: Not all map in path statistic still running. If we got to
        // speculate a path, we want to speculate only tasks of that path
        // group that actually still running.
        // If we got to this point, it means the task is still running

        TaskAttemptStatus latestMap = null;
        for (TaskAttemptId attemptId : getKnownAttempt(taskId)) {

          TaskAttemptStatus map = recentAttemptStatus.get(attemptId);
          if (map == null) {
            continue;
          }
          if (map.mapTransferRate > 0.0d) {
            // riza: only check the latest running map

            if ((latestMap == null) || (map.id.compareTo(latestMap.id) > 0)) {
              latestMap = map;
//              LOG.info("Got latest map " + latestMap.id + ", rate: "
//                  + latestMap.mapTransferRate + ", state: "
//                  + latestMap.taskState);
            }
          }
        }

        if (latestMap != null) {
          if (latestMap.containerHost != latestMap.lastDatanodeID.getHostName()) {
            // riza: We also only interested in speculating non-local task.

            if (latestMap.mapTransferRate < threshold) {
              // riza: the path is slower than threshold. Speculate!
              toSPeculate.add(latestMap.id.getTaskId());
              fastestRate = Math.max(fastestRate, latestMap.mapTransferRate);
            }
          }

          if ((slowestMap == null)
              || (slowestMap.mapTransferRate > latestMap.mapTransferRate)) {
              slowestMap = latestMap;
            }
        }
      }

      if (!toSPeculate.isEmpty()) {
        // riza: path group speculation
        LOG.info("PBSE-Read-3: Speculating " + toSPeculate.size()
            + " tasks, fastest rate " + fastestRate + " threshold " + threshold
            + " global avg rate " + globalTransferRate);

        for (TaskId taskId : toSPeculate) {
          // @Cesar: Ommit the tasks killed by slow shuffle here
          if(!shuffleTable.wasSpeculated(taskId)){	
        	  addSpeculativeAttempt(taskId);
        	  ++successes;
          }
          else{
        	  // @Cesar: Just log here
        	  LOG.warn("@Cesar: Just trying to speculate a map that was already killed by slow shuffle");
          }
        }
      } else {
        LOG.info("Nothing to speculate, global rate: " + globalTransferRate
            + " threshold: " + threshold + " Mbps"
            + " slowestTransferRate: " + slowestTransferRate
            + " already speculated " + numberSpeculationsAlready
            + " already finished " + numberFinishedAlready
            + " unknown attempt " + numberUnknownAttempt
            + " slowestMap rate: "
            + (slowestMap == null ? -1.0 : slowestMap.mapTransferRate));
      }
    }

    return successes;
  }
}
