package peno.htttp;

/**
 * A handler for game events.
 */
public interface GameHandler {

	/**
	 * Invoked when the game has started.
	 */
	public void gameStarted();

	/**
	 * Invoked when the game has stopped.
	 * 
	 * <p>
	 * Players should stop their robot and can clear its state.
	 * </p>
	 */
	public void gameStopped();

	/**
	 * Invoked when the game has paused.
	 * 
	 * <p>
	 * Players should stop their robot but retain its state.
	 * </p>
	 */
	public void gamePaused();

}
