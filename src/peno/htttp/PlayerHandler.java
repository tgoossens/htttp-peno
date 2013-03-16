package peno.htttp;

/**
 * A handler for player events.
 */
public interface PlayerHandler extends GameHandler {

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
	 * Invoked when a player has found their object.
	 * 
	 * @param playerID
	 *            The player identifier.
	 */
	public void playerFoundObject(String playerID);

}
