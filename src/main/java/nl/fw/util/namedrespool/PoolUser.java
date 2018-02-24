package nl.fw.util.namedrespool;

import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CountDownLatch;

/**
 * A resource(s) user, keeps track of lock-claims, locked resources and lock-state.
 * The resource user must know at forehand which resource(s) are needed, 
 * this may not change between lock/unlock of used resources.
 * <p>
 * Sets are not concurrent, {@link NamedResPool} synchronizes all actions on the sets.
 * <br>Sets used for locking ({@link #claimed}) contain
 * not the resource name but the resource administration object ({@link NamedRes}) related to the resource name.
 * This provides greater guarantee that unlocking always succeeds.
 * 
 * @author vanOekel
 *
 * @param <T> type of resource name (unique ID for resource)
 */
public class PoolUser<T> {

	public String name;

	/**
	 * The resources used, may NOT change when user is being used with {@link NamedResPool}.
	 */
	final Set<T> resUsed = new HashSet<T>();
	
	/**
	 * Lock priority: used to give longer waiting users priority for locking resources.  
	 */
	volatile int priority;
	final Set<NamedRes<T>> claimed = new HashSet<NamedRes<T>>();
	volatile CountDownLatch lockWait;
	volatile boolean lockDone;

	public PoolUser() {
		super();
	}
	
	public PoolUser(String userName) {
		this.name = userName;
	}
	
	@SuppressWarnings("unchecked")
	public PoolUser<T> setResUsed(T... resNames) {
		return setResUsed(Arrays.asList(resNames));
	}

	public PoolUser<T> setResUsed(Collection<T> resNames) {
		resUsed.clear();
		resUsed.addAll(resNames);
		return this;
	}

	public PoolUser<T> addResUsed(T resName) {
		resUsed.add(resName);
		return this;
	}
	
	/**
	 * Returns true if a lock for used resources is in place for this resource user.
	 */
	public boolean isLocked() {
		return lockDone;
	}

	/**
	 * Returns a shallow clone of the resource names used.
	 * See also {@link #isLocked()}.
	 */
	public Set<T> getResNames() {
		return new HashSet<T>(resUsed);
	}
	
	/** Prepares this user object for re-use. */
	protected void reset() {
		
		claimed.clear();
		priority = 0;
		lockWait = null;
		lockDone = false;
	}
	
	/**
	 * Returns name if name is set.
	 */
	@Override public String toString() {
		return (name == null ? super.toString() : name);
	}
	
}
