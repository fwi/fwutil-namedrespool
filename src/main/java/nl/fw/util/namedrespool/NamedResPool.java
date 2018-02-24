package nl.fw.util.namedrespool;

import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A named resource pool that hands out named resources to resource users.
 * All methods are thread-safe. 
 * Main methods {@link #lock(PoolUser)} and {@link #unlock(PoolUser)} use one shared lock (a fair {@link ReentrantLock})
 * to synchronize all locking actions and prevent deadlocks.
 * See also the package description for more info. 
 * 
 * @author vanOekel
 *
 * @param <T> type of resource name (unique ID for resource)
 */
public class NamedResPool<T> {

	private static final Logger log = LoggerFactory.getLogger(NamedResPool.class);

	/**
	 * The main lock that keeps all administration in order and prevents deadlocks and starvation.
	 */
	private final ReentrantLock poolLock = new ReentrantLock(true);

	/**
	 * The named resources being locked.
	 */
	private final ConcurrentHashMap<T, NamedRes<T>> resources = new ConcurrentHashMap<>();

	/**
	 * Thread-local resource users (based on "currentThread").
	 */
	private final ConcurrentHashMap<Thread, PoolUser<T>> threadUsers = new ConcurrentHashMap<>();

	/**
	 * The users working with the resources.
	 * Used to verify proper calling behavior and flush out programming mistakes.
	 */
	private final Set<PoolUser<T>> users = Collections.newSetFromMap(new ConcurrentHashMap<PoolUser<T>, Boolean>());

	/**
	 * Returns the internally used pool lock used by {@link #lock(PoolUser)} and {@link #unlock(PoolUser)}.
	 * Only use the returned lock if you know what you are doing.
	 */
	protected ReentrantLock getPoolLock() {
		return poolLock;
	}
	
	/* *** Thread based locking users. *** */
	
	/**
	 * See {@link #lock(Collection)}
	 */
	@SuppressWarnings("unchecked")
	public void lock(T... resNames) throws InterruptedException {
		lock(Arrays.asList(resNames));
	}

	/**
	 * Locks the given resources within the context of the current thread, no lock timeout is used (i.e wait forever).
	 * <br>See {@link #lock(PoolUser, long, TimeUnit)}.
	 * @param resNames The resources to lock.
	 */
	public void lock(Collection<T> resNames) throws InterruptedException {
		
		try {
			lock(resNames, -1L, TimeUnit.MILLISECONDS);
		} catch (TimeoutException ignored) {
			// will never happen.
		}
	}

	/**
	 * See {@link #lock(Collection, long, TimeUnit)}
	 */
	@SuppressWarnings("unchecked")
	public void lock(long timeout, TimeUnit tunit, T... resNames) throws InterruptedException, TimeoutException {
		lock(Arrays.asList(resNames), timeout, tunit);
	}

	/**
	 * Locks the given resources within the context of the current thread.
	 * <br>See {@link #lock(PoolUser, long, TimeUnit)}.
	 * @param resNames The resources to lock.
	 */
	public void lock(Collection<T> resNames, long timeout, TimeUnit tunit) throws InterruptedException, TimeoutException {

		PoolUser<T> user = new PoolUser<>();
		final Thread t = Thread.currentThread();
		user.name = t.getName();
		if (threadUsers.putIfAbsent(t, user) != null) {
			throw new IllegalStateException("Thread based resource user already locked resources. Use unlock method first. Resource user: " + user);
		}
		user.setResUsed(resNames);
		try {
			lock(user, timeout, tunit);
		} finally {
			if (!user.lockDone) {
				threadUsers.remove(t);
			}
		}
	}

	/**
	 * Locks the resources used by the pool-user, no lock timeout is used (i.e wait forever).
	 * <br>See {@link #lock(PoolUser, long, TimeUnit)}.
	 */
	public void lock(PoolUser<T> user) throws InterruptedException {
		
		try {
			lock(user, -1L, TimeUnit.MILLISECONDS);
		} catch (TimeoutException ignored) {
			// will never happen.
		}
	}

	/**
	 * Locks resources used by the given resource user.
	 * <br>A call to this method must be followed by a call to {@link #unlock(PoolUser)} if locking succeeded.
	 * If the lock failed, an exception is thrown and a call to {@link #unlock(PoolUser)} is not required.
	 * If a (runtime) exception is thrown, all lock-actions are rolled back
	 * and the given resource user can be re-used to retry a lock.
	 * <br>Usage example: <pre>
	 * resPool.lock(user);
	 * try {
	 * 	// use locked resources
	 * } finally {
	 * 	resPool.unlock(user);
	 * } </pre>
	 * See also the package description for more info. 
	 * @param user the resource user locking.
	 * @param timeout lock time-out. A value less than 0 is used as "no timeout" (wait forever).
	 * @param tunit lock time unit.
	 * @throws InterruptedException when current thread is interrupted while waiting for resource lock.
 	 * @throws TimeoutException when lock time-out =&gt; 0 and a resource is not available within the given lock time-out period.
	 */
	public void lock(PoolUser<T> user, long timeout, TimeUnit tunit) throws InterruptedException, TimeoutException {

		if (users.contains(user)) {
			throw new IllegalStateException("Resource user already locked resources. Use unlock method first. Resource user: " + user);
		}
		boolean lockComplete = false;
		poolLock.lock();
		try {
			for (T resName : user.resUsed) {
				NamedRes<T> r = resources.get(resName);
				if (r == null) {
					r = new NamedRes<T>();
					r.name = resName;
					resources.put(resName, r);
				}
				user.claimed.add(r);
			}
			users.add(user);
			boolean allAvailable = true;
			for (NamedRes<T> r : user.claimed) {
				r.claims.addFirst(user);
				if (allAvailable) {
					allAvailable = r.available;
				}
			}
			if (allAvailable) {
				lockComplete = tryLockAllRes(user);
			}
			if (!lockComplete) {
				user.lockWait = new CountDownLatch(1);
			}
		} finally {
			poolLock.unlock();
		}
		if (!lockComplete) {
			waitForLock(user, timeout, tunit);
		}
	}

	/**
	 * Wait for additional resource locks. These are handed out by the unlock method.
	 */
	private void waitForLock(PoolUser<T> user, long timeout, TimeUnit tunit) throws InterruptedException, TimeoutException {
		
		boolean lockComplete = false;
		/*
		 * Using the lockComplete helper variable prevents a race-condition.
		 * The "lockWait" is released after "user.lockDone" is set to true (see the unlock method).
		 * This leaves a small window in time where lockWait can return false but the lock was done anyway.
		 * Also, the final-block in this method with the unlock cleanup-code should trigger if there is an exception. 
		 */
		try {
			if (timeout < 0L) {
				user.lockWait.await();
			} else {
				user.lockWait.await(timeout, tunit);
			}
			lockComplete = user.lockDone;
			if (!lockComplete) {
				throw new TimeoutException("Unable to acquire resource lock within " + tunit.toMillis(timeout) + " ms. for user " + user);
			}
		} finally {
			if (!lockComplete) {
				unlock(user);
			}
		}
	}

	private boolean tryLockAllRes(PoolUser<T> user) {
	
		/*
		 * If all philosophers get forks in order, P3 will still wait despite forks available.
		 * This is because P2 has claimed forks before P3 and P2 is waiting on a fork used by P1.
		 * Break this unwanted lock but also update other user's priorities so that they will not starve.
		 */
		for (NamedRes<T> r : user.claimed) {
			for (PoolUser<T> ru : r.claims) {
				if (ru.priority > 0) {
					return false;
				}
			}
		}
		// lock all resources
		for (NamedRes<T> r : user.claimed) {
			r.claims.removeFirst();
			r.available = false;
			for (PoolUser<T> ru : r.claims) {
				ru.priority++;
			}
		}
		user.lockDone = true;
		return true;
	}
	
	/**
	 * Releases any resource locks held by the current thread.
	 * <br>See {@link #unlock(PoolUser)}.
	 */
	public void unlock() {
		
		final Thread t = Thread.currentThread();
		final PoolUser<T> user = threadUsers.get(t);
		if (user != null) {
			try {
				unlock(user);
			} finally {
				threadUsers.remove(t);
			}
		}
	}
	
	/**
	 * The counter-part of {@link #lock(PoolUser)}, always place a call for this method in a finally block.
	 * <br>If {@link PoolUser#lockDone} is true, locked resources are released ({@link NamedRes#available} is set to true).
	 * If {@link PoolUser#lockDone} is false, any claims for resource locks are removed. 
	 * <br>The given resource user is reset so that it can be used for locking again.
	 * @param user the resource user that was used for locking.
	 */
	public void unlock(PoolUser<T> user) {

		if (user == null) {
			log.warn("Cannot unlock resources for resource user null");
			return;
		}
		if (!users.contains(user)) {
			// cleanup already done after failed lock attempt.
			return;
		}
		// must have lock to guarantee consistent state of the resource registry
		poolLock.lock();
		try {
			final boolean hadLock = user.lockDone;
			List<PoolUser<T>> nextUsers = new LinkedList<>();
			for (NamedRes<T> r : user.claimed) {
				if (hadLock) {
					// free used resource
					r.available = true;
					if (!r.claims.isEmpty()) {
						// register waiting user to try lock
						nextUsers.add(r.claims.getLast());
					}
				} else {
					// remove unused claim
					if (r.claims.getLast() == user) {
						r.claims.removeLast();
						if (!r.claims.isEmpty() && r.available) {
							// register waiting user to try lock
							nextUsers.add(r.claims.getLast());
						}
					} else {
						r.claims.remove(user);
					}
				}
			}
			// transfer locks to waiting users
			for (PoolUser<T> nu : nextUsers) {
				tryLockAllResAvailable(nu);
			}
			// remove unused resources
			for (NamedRes<T> r : user.claimed) {
				if (r.claims.isEmpty() && r.available) {
					resources.remove(r.name);
				}
			}			
		} finally {
			poolLock.unlock();
		}
		// reset user lock variables so that user can be used again for locking
		user.reset();
		users.remove(user);
	}

	private boolean tryLockAllResAvailable(PoolUser<T> user) {
		
		// similar to tryLockAllRes, 
		// but all we know for sure is that the user is first in line for at least one resource.
		for (NamedRes<T> r : user.claimed) {
			if (!r.available) {
				return false;
			}
		}
		// all resources available
		for (NamedRes<T> r : user.claimed) {
			if (r.claims.getLast() == user) {
				continue;
			}
			for (PoolUser<T> ru : r.claims) {
				if (ru.priority > user.priority) {
					// prevent starvation
					if (log.isDebugEnabled()) {
						log.debug("Not prioritizing lock from " + user + " (" + user.priority 
								+ ") over lock from " + ru + " (" + ru.priority + ")" + " on " + r);
					}
					return false;
				}
			}
		}
		// lock all resources for user
		for (NamedRes<T> r : user.claimed) {
			boolean updatePrio = false;
			if (r.claims.getLast() == user) {
				r.claims.removeLast();
			} else {
				updatePrio = true;
				// prevent deadlock
				if (log.isDebugEnabled()) {
					log.debug("Prioritizing lock from " + user + " (" + user.priority + ") on " + r);
				}
				r.claims.remove(user);
			}
			r.available = false;
			if (updatePrio) {
				for (PoolUser<T> ru : r.claims) {
					ru.priority++;
				}
			}
		}
		user.lockDone = true;
		user.lockWait.countDown();
		return true;
	}

	/* *** utility methods *** */

	/**
	 * The number of locks from threads using this pool.
	 * Should be 0 if all threads have finished (e.g. after finishing a process).
	 */
	public int getSizeThreadUsers() {
		return threadUsers.size();
	}

	/**
	 * The number of {@link PoolUser}s using this pool.
	 * Should be 0 if all users have finished (e.g. after finishing a process).
	 */
	public int getSizeUsers() {
		return users.size();
	}
	
	/**
	 * The number of resources being locked via this pool.
	 * Should be 0 if all users have finished (e.g. after finishing a process).
	 */
	public int getSizeResources() {
		return resources.size();
	}

	/**
	 * Synchronizes on locking process and obtains the number of resource users 
	 * waiting to lock the named resource.
	 * <br>This is an expensive operation.
	 */
	public int getLocksWaiting(T resName) throws InterruptedException {

		NamedRes<T> r = resources.get(resName);
		if (r == null) {
			return 0;
		}
		int claims = 0;
		poolLock.lock();
		try {
			claims = r.claims.size();
		} finally {
			poolLock.unlock();
		}
		return claims;
	}

	/**
	 * Returns true if named resource is currently being used (i.e. is locked by a resource user).
	 * This is a cheap operation.
	 */
	public boolean isLocked(T resName) {

		NamedRes<T> r = resources.get(resName);
		return (r == null ? false : !r.available);
	}

	/**
	 * Returns true if all resources are unlocked and available.
	 * Use this method with (unit) testing to ensure locks are always unlocked.
	 */
	public boolean isAllUnlocked() {

		for (T resName : resources.keySet()) {
			if (isLocked(resName)) {
				return false;
			}
		}
		return true;
	}

}
