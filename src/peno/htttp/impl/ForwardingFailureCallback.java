package peno.htttp.impl;

import peno.htttp.Callback;

public class ForwardingFailureCallback<V> implements Callback<V> {

	private final Callback<V> delegate;

	public ForwardingFailureCallback(Callback<V> delegate) {
		this.delegate = delegate;
	}

	@Override
	public void onSuccess(V result) {
	}

	@Override
	public void onFailure(Throwable t) {
		delegate.onFailure(t);
	}

}
