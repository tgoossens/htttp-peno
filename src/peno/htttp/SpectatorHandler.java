package peno.htttp;

/**
 * A handler for spectator events.
 */
public interface SpectatorHandler extends GameHandler {

	/**
	 * Invoked when a player has determined their player number.
	 * 
	 * <p>
	 * Listening spectators simulating the world can use this to position the
	 * player on its starting position in the world before starting the game.
	 * </p>
	 * 
	 * @param playerDetails
	 *            The player details.
	 * @param playerNumber
	 *            The player number.
	 */
	public void playerRolled(PlayerDetails playerDetails, int playerNumber);

	/**
	 * Invoked when a player updates its state.
	 * 
	 * <p>
	 * The standard coordinate system has the X-axis running from left to right
	 * and the Y-axis from bottom to top. The angle of orientation is measured
	 * counterclockwise from the positive X-axis to the positive Y-axis.
	 * </p>
	 * 
	 * <p>
	 * Note: the player position is <strong>relative to its starting
	 * position</strong>. Implementations need to transform this into an
	 * absolute position themselves using the starting position corresponding to
	 * the player number from the maze file.
	 * </p>
	 * 
	 * @param playerDetails
	 *            The player details.
	 * @param playerNumber
	 *            The player number.
	 * @param x
	 *            The X-coordinate of the player's position.
	 * @param y
	 *            The Y-coordinate of the player's position.
	 * @param angle
	 *            The angle of the player's orientation.
	 * @param foundObject
	 *            True if the player has found their object.
	 */
	public void playerUpdate(PlayerDetails playerDetails, int playerNumber, long x, long y, double angle,
			boolean foundObject);

	/**
	 * Invoked when a player is about to travel over a seesaw.
	 * 
	 * <p>
	 * The player provides the barcode that has been read in front of the
	 * seesaw. This uniquely identifies the seesaw that he is traversing as well
	 * as the travel direction.
	 * </p>
	 * 
	 * @param playerID
	 *            The player identifier.
	 * @param playerNumber
	 *            The player number.
	 * @param barcode
	 *            The barcode at the side of the seesaw where the player starts
	 *            traveling.
	 */
	public void lockedSeesaw(String playerID, int playerNumber, int barcode);

	/**
	 * Invoked when a player is done traveling over a seesaw.
	 * 
	 * <p>
	 * The player provides the barcode that has been read in front of the
	 * seesaw. This uniquely identifies the seesaw that he traversed as well as
	 * the travel direction.
	 * </p>
	 * 
	 * <p>
	 * Listening spectators simulating the world should flip the state of the
	 * seesaw when the seesaw is unlocked. That is, after unlocking the seesaw,
	 * the side from which the player started traversing becomes closed and the
	 * side on which he ended up becomes open.
	 * </p>
	 * 
	 * @param playerID
	 *            The player identifier.
	 * @param playerNumber
	 *            The player number.
	 * @param barcode
	 *            The barcode at the side of the seesaw where the player started
	 *            traveling.
	 */
	public void unlockedSeesaw(String playerID, int playerNumber, int barcode);

}
