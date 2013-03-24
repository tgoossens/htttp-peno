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

	/**
	 * Invoked when the game has been won.
	 * 
	 * @param teamNumber
	 *            The winning team's number.
	 */
	public void gameWon(int teamNumber);

	/**
	 * Invoked when a player attempts to join the game.
	 * 
	 * @param playerID
	 *            The player identifier.
	 */
	public void playerJoining(String playerID);

	/**
	 * Invoked when a player has joined the game.
	 * 
	 * @param playerID
	 *            The player identifier.
	 */
	public void playerJoined(String playerID);

	/**
	 * Invoked when a player has disconnected from the game.
	 * 
	 * @param playerID
	 *            The player identifier.
	 * @param reason
	 *            The reason for the disconnect.
	 */
	public void playerDisconnected(String playerID, DisconnectReason reason);

	/**
	 * Invoked when a player changes his ready state.
	 * 
	 * @param playerID
	 *            The player identifier.
	 * @param isReady
	 *            The player's new ready state.
	 */
	public void playerReady(String playerID, boolean isReady);

	/**
	 * Invoked when a player has found their object.
	 * 
	 * @param playerID
	 *            The player identifier.
	 * @param playerNumber
	 *            The player number.
	 */
	public void playerFoundObject(String playerID, int playerNumber);

}
