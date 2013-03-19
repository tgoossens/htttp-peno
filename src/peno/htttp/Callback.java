package peno.htttp;

/**
 * A callback for accepting the results of a task asynchronously.
 */
public interface Callback<V> {

	/**
	 * Invoked with the result of the asynchronous task when it is successful.
	 */
	void onSuccess(V result);

	/**
	 * Invoked when an asynchronous task fails or is canceled.
	 */
	void onFailure(Throwable t);

}
