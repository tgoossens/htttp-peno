package peno.htttp;

import java.util.ArrayList;
import java.util.List;

/**
 * A maze tile shared between team partners.
 * 
 * <p>
 */
public class Tile {

	private final long x;
	private final long y;
	private final String token;

	public Tile(long x, long y, String token) {
		this.x = x;
		this.y = y;
		this.token = token;
	}

	/**
	 * Get the X-coordinate of the relative position of this maze tile.
	 */
	public long getX() {
		return x;
	}

	/**
	 * Get the Y-coordinate of the relative position of this maze tile.
	 */
	public long getY() {
		return y;
	}

	/**
	 * Get the tile token of this maze tile.
	 * 
	 * <p>
	 * The syntax for tile tokens is specified in the &quot;Maze and barcode
	 * specification&quot;.
	 * </p>
	 */
	public String getToken() {
		return token;
	}

	static Tile read(List<Object> in) {
		long x = ((Number) in.get(0)).longValue();
		long y = ((Number) in.get(1)).longValue();
		String token = (String) in.get(2);
		return new Tile(x, y, token);
	}

	List<Object> write() {
		List<Object> out = new ArrayList<Object>();
		out.add(getX());
		out.add(getY());
		out.add(getToken());
		return out;
	}

}
