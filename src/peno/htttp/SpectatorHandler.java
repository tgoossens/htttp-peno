package peno.htttp;

/**
 * A handler for spectator events.
 */
public interface SpectatorHandler extends GameHandler {

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
	 * @param playerID
	 *            The player identifier.
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
	public void playerUpdate(String playerID, int playerNumber, double x, double y, double angle, boolean foundObject);

	/**
	 * Invoked when a player is about to travel over a seesaw.
	 * 
	 * <p>
	 * The player will provide the barcode he read in front of the seesaw he
	 * wants to access. Based on the position of the player and the barcode it
	 * is possible to derive <br>
	 * 1. Which seesaw the robot is crossing. <br>
	 * 2. In what direction the seesaw must flip
	 * </p>
	 * 
	 * @param playerID
	 *            The player identifier.
	 * @param playerNumber
	 *            The player number.
	 * @param barcode
	 *            The barcode read before accessing the seesaw
	 */
	public void lockedSeesaw(String playerID, int playerNumber, int barcode);

	/**
	 * Invoked when a player is done travelling over a seesaw
	 * 
	 * @param playerID
	 *            The player identifier.
	 * @param playerNumber
	 *            The player number.
	 * @param barcode
	 *            The barcode read before accessing the seesaw
	 */
	public void unlockedSeesaw(String playerID, int playerNumber, int barcode);

}
