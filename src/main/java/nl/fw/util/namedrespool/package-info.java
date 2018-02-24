/**
 * A locking mechanism for named shared resources that prevents deadlocks and starvation.
 * The locking mechanism requires resource users to know at forehand 
 * which resources need to be locked to prevent deadlocks and starvation.
 * <br>Usage example: <pre>
 * resPool.lock("F1", "F2");
 * try {
 * 	// use locked resources
 * } finally {
 * 	resPool.unlock();
 * } </pre>
 * In the example above an internal PoolUser is created using the <tt>Thread.currentThread()</tt> context.
 * The internal PoolUser is removed by the <tt>unlock()</tt> method.
 * If the <tt>Thread.currentThread()</tt> context is potentially unreliable (e.g. <tt>unlock()</tt>
 * is called by a different thread as <tt>lock()</tt>), a PoolUser needs to be created manually.
 * <br> The NamedResPool adds resources that need a lock and removes them when no lock request
 * or locks are open on a resource. This allows for any number of resources to be locked
 * and it is not needed at forehand to know which named resources are available.
 * <p>  
 * The caller may NOT lock additional resources until previous locked resources are unlocked.
 * This is because the startvation prevention logic can cause deadlocks 
 * eventhough the caller is not overlapping resource locks.
 * <br>E.g. the following <i>is</i> allowed: <pre>
 * user = new PoolUser("user").setResUsed("F1");
 * resPool.lock(user);
 * try {
 * 	// use locked resource F1
 * 	resPool.unlock(user);
 * 	user.addResUsed("F2");
 * 	resPool.lock(user);
 * 	// use locked resources F1 and F2
 * } finally {
 * 	resPool.unlock(user);
 * } </pre>
 * But the following is <b>not</b> allowed: <pre>
 * user1 = new PoolUser("user1").setResUsed(F1);
 * resPool.lock(user1);
 * try {
 * 	// use locked resource F1 from user1
 * 	user2 = new PoolUser("user2").setResUsed(F2);
 * 	resPool.lock(user2); // this could cause a deadlock
 * 	try {
 * 	} finally {
 *		resRef.unlock(user2);	 	
 * 	}
 * } finally {
 * 	resPool.unlock(user1);
 * } </pre>
 * 
 */ 
package nl.fw.util.namedrespool;