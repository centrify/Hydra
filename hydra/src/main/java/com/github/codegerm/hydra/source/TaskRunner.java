package com.github.codegerm.hydra.source;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.apache.flume.Context;
import org.apache.flume.Event;
import org.apache.flume.FlumeException;
import org.apache.flume.channel.ChannelProcessor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.github.codegerm.hydra.event.StatusEventBuilder;
import com.github.codegerm.hydra.handler.AbstractHandler;
import com.github.codegerm.hydra.handler.HandlerFactory;
import com.github.codegerm.hydra.handler.HibernateHandler;
import com.github.codegerm.hydra.task.Result;
import com.github.codegerm.hydra.task.Task;
import com.github.codegerm.hydra.task.TaskRegister;
import com.github.codegerm.hydra.task.TaskRegisterFactory;
import com.github.codegerm.hydra.utils.CommandUtils;

/**
 * Bundle all table fetching actions into one task, if one action failed, fail
 * the entire task.
 * 
 * @author yufan.liu
 *
 */
public class TaskRunner {

	public interface ChannelProcessorProvider {
		ChannelProcessor provide();
	}

	private static final Logger LOG = LoggerFactory.getLogger(TaskRunner.class);

	private ChannelProcessorProvider channelProcessorProvider;
	private Context context;
	private long timeout;
	private long cmdTimeout;
	private CommandUtils cmdUtil;
	private String preProcessingCmd;
	private String preProcessor;
	private ExecutorService executor;
	private ExecutorService mainExecutor;
	private SqlRunnable runner;
	private String handlerString;
	private String taskQueueId;
	private TaskRegister register;
	private long serverTimeout;

	public TaskRunner(ChannelProcessorProvider provider) {
		this.channelProcessorProvider = provider;
	}

	public TaskRunner(final ChannelProcessor channelProcessor) {
		if (channelProcessor == null) {
			throw new IllegalArgumentException("Channel processor is null");
		}
		this.channelProcessorProvider = new ChannelProcessorProvider() {

			@Override
			public ChannelProcessor provide() {
				return channelProcessor;
			}
		};
	}

	public void configure(Context context) {
		this.context = context;
		int threadNum = context.getInteger(SqlSourceUtil.WORKER_THREAD_NUM_KEY, SqlSourceUtil.DEFAULT_THREAD_NUM);
		timeout = context.getLong(SqlSourceUtil.TIMEOUT_KEY, SqlSourceUtil.DEFAULT_TIMEOUT);
		cmdTimeout = context.getLong(SqlSourceUtil.CMD_TIMEOUT_KEY, SqlSourceUtil.DEFAULT_CMD_TIMEOUT);
		preProcessingCmd = context.getString(SqlSourceUtil.CMD_KEY);
		preProcessor =  context.getString(SqlSourceUtil.PRE_PROCESSOR_KEY);
		handlerString = context.getString(SqlSourceUtil.HANDLER_KEY);
		if (preProcessingCmd != null)
			cmdUtil = new CommandUtils(cmdTimeout);
		executor = Executors.newFixedThreadPool(threadNum);
		mainExecutor = Executors.newSingleThreadExecutor();
		taskQueueId =  context.getString(SqlSourceUtil.TASK_QUEUE_ID);
			
		if(taskQueueId == null){
			LOG.info("No task queue id defined, use default queue");
			register = TaskRegister.getInstance();
		} else {
			LOG.info("Task queue id: " + taskQueueId );
			register = TaskRegisterFactory.getInstance().getPutInstance(taskQueueId);
		}
		register.cleanTaskQueue();
		serverTimeout = context.getLong(SqlSourceUtil.SERVER_TIMEOUT_KEY, SqlSourceUtil.DEFAULT_SERVER_TIMEOUT);
	}

	public void start() {
		runner = new SqlRunnable();
		mainExecutor.execute(runner);
	}

	public void stop() {

		executor.shutdown();
		// kill the task queue listener
		Task killTask = new Task(true);
		register.addTask(killTask);
		mainExecutor.shutdownNow();
		while (!executor.isTerminated()) {
			LOG.debug("Waiting for exec executor service to stop");
			try {
				executor.awaitTermination(500, TimeUnit.MILLISECONDS);
			} catch (InterruptedException e) {
				LOG.debug("Interrupted while waiting for exec executor service " + "to stop. Just exiting.");
				Thread.currentThread().interrupt();
			}
		}
	}

	private void processEvent(Event event) {
		if (channelProcessorProvider != null) {
			channelProcessorProvider.provide().processEvent(event);
		}
	}

	private class SqlRunnable implements Runnable {

		private Task task;
		private String modelId;
		private String snapshotId;
		private String createBy;
		private Map<String, String> entitySchemas;
		

		public void setEntitySchemas(Map<String, String> entitySchemas) {
			this.entitySchemas = entitySchemas;
		}

		public void setModelId(String modelId) {
			this.modelId = modelId;
		}
		
		public void setCreateBy(String createBy) {
			this.createBy = createBy;
		}
		

		@Override
		public void run() {
			try {
				while (true) {
					task = register.getTaskByTake();
					if (task.getKillSignal()) {
						LOG.info("Kill signal received, stopping the task listener");
						return;
					}
					setModelId(task.getModelId());
					setEntitySchemas(task.getEntitySchemas());
					Map<String, String> extraInfo = task.getExtraInfo();
					if(extraInfo!=null){
						setCreateBy(extraInfo.get(SqlSourceUtil.CREATE_BY_KEY));
					}
					
					execute();
				}
			} catch (Exception e) {
				LOG.error("task error: ", e);
				Thread.currentThread().interrupt();
			}
		}

		public Boolean execute() {
			snapshotId = modelId + System.currentTimeMillis();
			LOG.info("start snapshot: " + snapshotId);
			if (entitySchemas == null) {
				throw new FlumeException("Entity Schemas are not initiated");
			}
			register.assignSnapshotId(snapshotId, task);
			//processEvent(StatusEventBuilder.buildSnapshotBeginEvent(snapshotId, modelId));
			// Run pre-processing script/cmd
			String cmdError = "";

			try {
				
				if(preProcessor != null){
					try {
						PreProcessor processor = PreProcessorFactory.createProcessor(preProcessor);
						processor.process(context);
					} catch (Exception e) {
						String msg = "pre-processing failed, sending fail result: " + e.getMessage();
						LOG.error(msg);
						processEvent(StatusEventBuilder.buildSnapshotBeginEvent(snapshotId, modelId, serverTimeout, createBy));			
						processEvent(StatusEventBuilder.buildSnapshotErrorEvent(snapshotId, modelId, msg));
						return false;
					}
				}
				
				

				if (preProcessingCmd != null) {
					LOG.info("Pre-processing enabled: " + preProcessingCmd);
					try {
						cmdError = cmdUtil.executeCmd(preProcessingCmd);
					} catch (Exception e) {
						String msg = "pre-processing failed, sending fail result: " + e.getMessage();
						LOG.error(msg);
						processEvent(StatusEventBuilder.buildSnapshotBeginEvent(snapshotId, modelId, serverTimeout, createBy));			
						processEvent(StatusEventBuilder.buildSnapshotErrorEvent(snapshotId, modelId, msg));
						return false;
					}
					if (cmdError != null && !cmdError.isEmpty()) {
						String msg = "pre-processing failed, sending fail result: " + cmdError;
						LOG.error(msg);
						processEvent(StatusEventBuilder.buildSnapshotBeginEvent(snapshotId, modelId, serverTimeout, createBy));
						processEvent(StatusEventBuilder.buildSnapshotErrorEvent(snapshotId, modelId, msg));
						return false;
					}

				}
				
				processEvent(StatusEventBuilder.buildSnapshotBeginEvent(snapshotId, modelId, serverTimeout, createBy));

				List<Callable<Boolean>> taskList = new ArrayList<Callable<Boolean>>();

				for (Entry<String, String> entry : entitySchemas.entrySet()) {
					LOG.info("Starting worker thread for table [" + entry.getKey() + "]");
					AbstractHandler handler = null;
					if(handlerString == null || handlerString.isEmpty()) {
						handler = new HibernateHandler(snapshotId, context,
								channelProcessorProvider.provide(), modelId, entry.getKey(), entry.getValue());
					} else {
						handler = HandlerFactory.createHandler(handlerString);
						handler.initialize(snapshotId, context,
								channelProcessorProvider.provide(), modelId, entry.getKey(), entry.getValue());
						handler.configure();
					}
					taskList.add(handler);
				}
				List<Future<Boolean>> result = executor.invokeAll(taskList, timeout, TimeUnit.MILLISECONDS);
				boolean isSuccess = true;
				for (Future<Boolean> future : result) {
					if (future.isCancelled()) {
						LOG.error("Task timeout");
						isSuccess = false;
						break;
					}
					if (future.get() == null || !future.get()) {
						isSuccess = false;
						break;
					}

				}

				if (isSuccess)
					processEvent(StatusEventBuilder.buildSnapshotEndEvent(snapshotId, modelId));
				else {
					String msg = "Tasks failed because one of tasks failed or timeout";
					LOG.error(msg);
					processEvent(StatusEventBuilder.buildSnapshotErrorEvent(snapshotId, modelId, msg));
				}

				Result runningResult = new Result(snapshotId, result);
				register.addResult(runningResult);
				return true;

			} catch (Exception e) {
				LOG.error("Error procesing", e);
				return false;
			} finally {
				register.markTaskDone(snapshotId);
			}
		}
	}

}
