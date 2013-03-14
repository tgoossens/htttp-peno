package peno.htttp;

/**
 * A handler for game events.
 */
public interface Handler {

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
	 * Invoked when the player numbers have been rolled.
	 * 
	 * @param playerNumber
	 *            The local player's player number.
	 */
	public void gameRolled(int playerNumber);

	/**
	 * Invoked when a player has joined the game.
	 * 
	 * @param playerID
	 *            The player identifier.
	 */
	public void playerJoined(String playerID);

	/**
	 * Invoked when a player has left the game.
	 * 
	 * @param playerID
	 *            The player identifier.
	 */
	public void playerLeft(String playerID);

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
	 * Invoked when a player updates its position.
	 * 
	 * @param playerID
	 *            The player identifier.
	 * @param x
	 *            The X-coordinate of the player's position.
	 * @param y
	 *            The Y-coordinate of the player's position.
	 * @param angle
	 *            The angle of the player's orientation.
	 */
	public void playerPosition(String playerID, double x, double y, double angle);

	/**
	 * Invoked when a player has found their object.
	 * 
	 * @param playerID
	 *            The player identifier.
	 */
	public void playerFoundObject(String playerID);

}
