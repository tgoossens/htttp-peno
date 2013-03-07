package peno.htttp.impl;

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public class PlayerRegister {

	private final Map<String, PlayerInfo> confirmed = new HashMap<String, PlayerInfo>();
	private final Map<String, Map<String, PlayerInfo>> voted = new HashMap<String, Map<String, PlayerInfo>>();
	private final Set<String> missing = new HashSet<String>();

	/**
	 * Confirm a client's player and add it to the registry.
	 * 
	 * <p>
	 * When a player with the same player identifier was missing before, it is
	 * no longer missing.
	 * </p>
	 * 
	 * @param player
	 *            The player.
	 */
	public void confirm(PlayerInfo player) {
		String playerID = player.getPlayerID();
		String clientID = player.getClientID();

		// Add to confirmed
		confirmed.put(playerID, player);

		// Remove from voted
		Map<String, PlayerInfo> votedClients = voted.get(playerID);
		if (votedClients != null) {
			votedClients.remove(clientID);
		}

		// Remove from missing
		missing.remove(playerID);
	}

	/**
	 * Vote for a client's player.
	 * 
	 * <p>
	 * When a player with the same player identifier was missing before, it is
	 * no longer missing.
	 * </p>
	 * 
	 * @param player
	 *            The player.
	 */
	public void vote(PlayerInfo player) {
		String playerID = player.getPlayerID();
		String clientID = player.getClientID();

		// Add to voted clients
		Map<String, PlayerInfo> clients = voted.get(playerID);
		if (clients == null) {
			clients = new HashMap<String, PlayerInfo>();
			voted.put(playerID, clients);
		}
		clients.put(clientID, player);

		// Remove missing
		if (missing.contains(playerID)) {
			missing.remove(playerID);
		}
	}

	/**
	 * Remove a client's player from the registry.
	 * 
	 * @param clientID
	 *            The client identifier.
	 * @param playerID
	 *            The player identifier.
	 */
	public void remove(String clientID, String playerID) {
		// Remove confirmed player if needed
		if (isConfirmed(clientID, playerID)) {
			confirmed.remove(playerID);
		}

		// Remove voted client
		Map<String, PlayerInfo> votedClients = voted.get(playerID);
		if (votedClients != null) {
			// Remove from client map
			votedClients.remove(clientID);
			if (votedClients.isEmpty()) {
				// Clean up empty client map
				voted.remove(playerID);
			}
		}
	}

	/**
	 * Check if there is a confirmed player with the given player identifier.
	 * 
	 * @param playerID
	 *            The player identifier.
	 */
	public boolean hasConfirmed(String playerID) {
		return confirmed.containsKey(playerID);
	}

	/**
	 * Check whether the given player is confirmed.
	 * 
	 * @param playerID
	 *            The player identifier.
	 */
	public boolean isConfirmed(String clientID, String playerID) {
		return hasConfirmed(playerID)
				&& getConfirmed(playerID).getClientID().equals(clientID);
	}

	/**
	 * Get the confirmed player with the given player identifier.
	 * 
	 * @param playerID
	 *            The player identifier.
	 */
	public PlayerInfo getConfirmed(String playerID) {
		return confirmed.get(playerID);
	}

	/**
	 * Get all currently confirmed players.
	 */
	public Collection<PlayerInfo> getConfirmed() {
		return Collections.unmodifiableCollection(confirmed.values());
	}

	/**
	 * Check if a client's player can join.
	 * 
	 * @param clientID
	 *            The client identifier.
	 * @param playerID
	 *            The player identifier.
	 */
	public boolean canJoin(String clientID, String playerID) {
		// Check if colliding with confirmed player
		if (hasConfirmed(playerID)) {
			// Needs to match the confirmed player
			return isConfirmed(clientID, playerID);
		}

		// Check if already voted for given player
		return !hasVoted(playerID);
	}

	/**
	 * Check if a player with the given player identifier has been voted for.
	 * 
	 * @param playerID
	 *            The player identifier.
	 */
	public boolean hasVoted(String playerID) {
		Map<String, PlayerInfo> votedClients = voted.get(playerID);
		return votedClients != null && !votedClients.isEmpty();
	}

	public PlayerInfo getVoted(String clientID, String playerID) {
		Map<String, PlayerInfo> votedClients = voted.get(playerID);
		if (votedClients != null) {
			return votedClients.get(clientID);
		} else {
			return null;
		}
	}

	/**
	 * Check whether the given player is currently missing.
	 * 
	 * @param playerID
	 *            The player identifier.
	 */
	public boolean isMissing(String playerID) {
		return missing.contains(playerID);
	}

	/**
	 * Check whether there are any players currently missing.
	 */
	public boolean hasMissing() {
		return !missing.isEmpty();
	}

	/**
	 * Get a set of all currently missing players.
	 */
	public Set<String> getMissing() {
		return Collections.unmodifiableSet(missing);
	}

	/**
	 * Clear all missing players.
	 */
	public void clearMissing() {
		missing.clear();
	}

	/**
	 * Mark the given player as missing.
	 * 
	 * @param playerID
	 *            The player identifier.
	 */
	public void setMissing(String playerID) {
		confirmed.remove(playerID);
		voted.remove(playerID);
		missing.add(playerID);
	}

	/**
	 * Get the amount of confirmed players.
	 */
	public int getNbPlayers() {
		return confirmed.size();
	}

	/**
	 * Clear all players in the register.
	 */
	public void clear() {
		confirmed.clear();
		voted.clear();
		clearMissing();
	}

}
