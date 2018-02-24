package nl.fw.util.namedrespool;

import java.util.LinkedList;

/**
 * Named resource usage tracker, keeps track of named resource state and claims by resource users.
 * 
 * @author vanOekel
 *
 * @param <T> type of resource name (unique ID for resource)
 */
public class NamedRes<T> {

	T name;
	
	volatile boolean available = true;
	
	/**
	 * Contains awaiting locks for the resource.
	 * This list is not concurrent, {@link NamedResPool} synchronizes all actions on this list.
	 */
	final LinkedList<PoolUser<T>> claims = new LinkedList<PoolUser<T>>();

	/**
	 * Returns name if name is set.
	 */
	@Override public String toString() {
		return (name == null ? super.toString() : name.toString());
	}
}
