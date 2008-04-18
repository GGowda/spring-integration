/*
 * Copyright 2002-2008 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.integration.scheduling;

import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import org.springframework.integration.util.ErrorHandler;
import org.springframework.util.Assert;

/**
 * An implementation of {@link MessagingTaskScheduler} that understands
 * {@link PollingSchedule PollingSchedules}.
 * 
 * @author Mark Fisher
 */
public class SimpleMessagingTaskScheduler extends AbstractMessagingTaskScheduler {

	private final Log logger = LogFactory.getLog(this.getClass());

	private final ScheduledExecutorService executor;

	private volatile ErrorHandler errorHandler;

	private final Set<Runnable> pendingTasks = new CopyOnWriteArraySet<Runnable>();

	private volatile boolean running;

	private final Object lifecycleMonitor = new Object();


	public SimpleMessagingTaskScheduler(ScheduledExecutorService executor) {
		Assert.notNull(executor, "'executor' must not be null");
		this.executor = executor;
	}


	public void setErrorHandler(ErrorHandler errorHandler) {
		this.errorHandler = errorHandler;
	}

	public boolean isRunning() {
		return this.running;
	}

	public void start() {
		synchronized (this.lifecycleMonitor) {
			if (this.running) {
				return;
			}
			this.running = true;
			for (Runnable task : this.pendingTasks) {
				if (logger.isDebugEnabled()) {
					logger.debug("scheduling task: " + task);
				}
				this.schedule(task);
			}
			this.pendingTasks.clear();
			if (logger.isInfoEnabled()) {
				logger.info("task scheduler started successfully");
			}
		}
	}

	public void stop() {
		synchronized (this.lifecycleMonitor) {
			if (this.running) {
				this.executor.shutdownNow();
				this.running = false;
			}
		}
	}

	@Override
	public ScheduledFuture<?> schedule(Runnable task) {
		if (!this.running) {
			this.pendingTasks.add(task);
			return null;
		}
		Schedule schedule = (task instanceof MessagingTask) ? ((MessagingTask) task).getSchedule() : null;
		MessagingTaskRunner runner = new MessagingTaskRunner(task);
		if (schedule == null) {
			return this.executor.schedule(runner, 0, TimeUnit.MILLISECONDS);
		}
		if (schedule instanceof PollingSchedule) {
			PollingSchedule ps = (PollingSchedule) schedule;
			if (ps.getPeriod() <= 0) {
				runner.setShouldRepeat(true);
				return this.executor.schedule(runner, ps.getInitialDelay(), ps.getTimeUnit());
			}
			if (ps.getFixedRate()) {
				return this.executor.scheduleAtFixedRate(runner, ps.getInitialDelay(), ps.getPeriod(), ps.getTimeUnit());
			}
			return this.executor.scheduleWithFixedDelay(runner, ps.getInitialDelay(), ps.getPeriod(), ps.getTimeUnit());
		}
		throw new UnsupportedOperationException(this.getClass().getName() + " does not support schedule type '"
				+ schedule.getClass().getName() + "'");
	}


	private class MessagingTaskRunner implements Runnable {

		private final Runnable task;

		private volatile boolean shouldRepeat;


		public MessagingTaskRunner(Runnable task) {
			this.task = task;
		}

		public void setShouldRepeat(boolean shouldRepeat) {
			this.shouldRepeat = shouldRepeat;
		}

		public void run() {
			try {
				this.task.run();
			}
			catch (Throwable t) {
				if (errorHandler != null) {
					errorHandler.handle(t);
				}
				else if (logger.isWarnEnabled()) {
					logger.warn("error occurred in task but no 'errorHandler' is available", t);
				}
			}
			if (this.shouldRepeat) {
				MessagingTaskRunner runner = new MessagingTaskRunner(this.task);
				runner.setShouldRepeat(true);
				executor.execute(runner);
			}
		}
	}

}
