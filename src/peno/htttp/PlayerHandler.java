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

}
