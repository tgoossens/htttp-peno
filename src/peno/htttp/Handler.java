package peno.htttp;

import java.util.Collection;

/**
 * A handler for game events.
 */
public interface Handler {

	/**
	 * Invoked when the game has started.
	 * 
	 * @param players
	 *            The list of participating players, as a collection of player
	 *            identifiers.
	 */
	public void gameStarted(Collection<String> players);

	/**
	 * Invoked when the game has stopped.
	 */
	public void gameStopped();

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

}
