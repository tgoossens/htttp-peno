package peno.htttp.impl;

import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicLong;

public class NamedThreadFactory implements ThreadFactory {

	private final String nameFormat;
	private final AtomicLong count = new AtomicLong(0);

	private final ThreadFactory backingThreadFactory = Executors.defaultThreadFactory();

	/**
	 * Creates a new named {@link ThreadFactory} builder.
	 * 
	 * @param nameFormat
	 *            a {@link String#format(String, Object...)}-compatible format
	 *            String, to which a unique integer (0, 1, etc.) will be
	 *            supplied as the single parameter. This integer will be unique
	 *            to the built instance of the ThreadFactory and will be
	 *            assigned sequentially.
	 */
	public NamedThreadFactory(String nameFormat) {
		String.format(nameFormat, 0); // fail fast if the format is bad or null
		this.nameFormat = nameFormat;
	}

	@Override
	public Thread newThread(Runnable runnable) {
		Thread thread = backingThreadFactory.newThread(runnable);
		thread.setName(String.format(nameFormat, count.getAndIncrement()));
		return thread;
	}

}
