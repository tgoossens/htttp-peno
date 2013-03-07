package peno.htttp.impl;

import java.util.concurrent.atomic.AtomicInteger;

public class RequestProvider {

	private final AtomicInteger counter = new AtomicInteger();

	public int nextRequestId() {
		return counter.incrementAndGet();
	}

}
