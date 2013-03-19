package peno.htttp.impl;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class RequestProvider {

	private final AtomicInteger counter = new AtomicInteger();

	private final ScheduledExecutorService executor = Executors.newScheduledThreadPool(1, factory);
	private static final ThreadFactory factory = new NamedThreadFactory("HTTTP-Request-%d");

	public int nextRequestId() {
		return counter.incrementAndGet();
	}

	public ScheduledFuture<?> scheduleTimeout(Runnable command, long timeout) {
		return executor.schedule(command, timeout, TimeUnit.MILLISECONDS);
	}

	public void terminate() {
		if (!executor.isShutdown()) {
			executor.shutdown();
		}
	}

}
