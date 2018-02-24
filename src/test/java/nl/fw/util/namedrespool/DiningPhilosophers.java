package nl.fw.util.namedrespool;

import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Test for {@link NamedResPool} using the "dining philosophers problem", see also
 * <br>{@code http://en.wikipedia.org/wiki/Dining_philosophers_problem}
 * <br>When this class is run (via the main-method), 
 * the shown/logged (on INFO-level) wait-times refer to the time waiting for a lock of used resources
 * (measured in milliseconds).
 * <br>Note: put debug logging OFF to prevent a flood of messages.
 * <br>A higher eat-time should result in higher lock wait-times.
 * A higher think-time should only result in less meals. 
 * 
 * @author vanOekel
 *
 */
public class DiningPhilosophers {

	private static final Logger log = LoggerFactory.getLogger(DiningPhilosophers.class);

	/* Total runtime. */
	public static long DINING_TIME_MS = 3_000L;
	
	/* Set anywhere between 1 and 100 */
	public static int PHILOSOPHERS = 5;
	
	/* Set anywhere between 0 and the amount of PHILOSOPHERS */
	public static int FORKS_PER_PHILOSOPHER = 2;
	
	/* Positive number or 0 to turn eat-delay off. */
	public static long EAT_TIME_MIN_MS = 50L;
	
	/* Random max. amount added to EAT_TIME_MIN_MS */
	public static int EAT_TIME_RANDOM_MS = 20;

	/* How long to wait for forks, value -1 is used as infinite wait time. */
	//public static long MAX_WAIT_TIME_MS = -1L;
	public static long MAX_WAIT_TIME_MS = EAT_TIME_MIN_MS + EAT_TIME_RANDOM_MS + 1L;
	
	/* Positive number or 0 to turn think-delay off. */
	public static long THINK_TIME_MS = 25L;
	
	/* 
	 * Philosophers first meal occurs one by one (true) or all at once (false).
	 * An ordered start combined with a fixed meal time should give repeatable results, 
	 * but it does not due to debug printing output?
	 */ 
	public static boolean ORDERED_START = true;
	
	public static void main(String[] args) {
		
		ThreadPoolExecutor tp = (ThreadPoolExecutor) Executors.newCachedThreadPool();
		try {
			DiningPhilosophers dp = new DiningPhilosophers();
			dp.setup();
			dp.dine(tp);
		} catch (Exception e) {
			log.error("Diner was a mess.", e);
		} finally {
			tp.shutdownNow();
		}
		log.info("Finished");
	}
	
	List<Philosopher> philosophers;
	// Need forks to be final because it is used in runnable Philosopher class. 
	final NamedResPool<String> forks = new NamedResPool<String>();

	void setup() {
		
		philosophers = new LinkedList<Philosopher>();
		for (int i = 1; i < PHILOSOPHERS + 1; i++) {
			Philosopher p = new Philosopher();
			philosophers.add(p);
			p.pnumber = i;
			p.name = "P" + i;
			int f = i;
			for (int j = 0; j < FORKS_PER_PHILOSOPHER; j++) {
				p.forksUsed.add("F" + f);
				f++;
				if (f > PHILOSOPHERS) {
					f = 1;
				}
			}
		}
	}
	
	final CountDownLatch tableReady = new CountDownLatch(PHILOSOPHERS);
	final CountDownLatch tableEmpty = new CountDownLatch(PHILOSOPHERS);
	
	// These numbers show in debug logging how many philosophers are doing what (eat sleep rave repeat).
	final AtomicInteger eaters = new AtomicInteger();
	final AtomicInteger thinkers = new AtomicInteger();
	final AtomicInteger waiters = new AtomicInteger();

	void dine(ThreadPoolExecutor tp) {
		
		for (Philosopher p : philosophers) {
			tp.execute(p);
		}
		try {
			if (!tableReady.await(1_000L, TimeUnit.MILLISECONDS)) {
				throw new RuntimeException("Missing one or more philosophers at the table.");
			}
			Thread.sleep(DINING_TIME_MS);
		} catch (Exception e) {
			log.error("Dining etiquette broken.", e);
		} finally {
			for (Philosopher p : philosophers) {
				p.stop = true;
			}
			try {
				// how long to wait for all philosophers to finish, not an exact science
				long waitTime = 500L + EAT_TIME_MIN_MS + EAT_TIME_RANDOM_MS + THINK_TIME_MS;
				tableEmpty.await(waitTime, TimeUnit.MILLISECONDS);
			} catch (Exception ignored) {}
			if (log.isInfoEnabled()) {
				StringBuilder sb = new StringBuilder("Philosopher stats:");
				for (Philosopher p : philosophers) {
					sb.append('\n').append(p.name).append(' ').append(p.getStats());
				}
				log.info(sb.toString());
			}
			if (!forks.isAllUnlocked()) {
				log.warn("One or more philosophers lost a fork.");
			}
			if (forks.getSizeUsers() > 0) {
				log.warn("One or more philosophers created by threads was not removed from pool.");
			}
			if (forks.getSizeThreadUsers() > 0) {
				log.warn("One or more threaded philosophers was not removed from pool.");
			}
		}
	}	
	
	class Philosopher implements Runnable {
		
		volatile boolean stop;
		int pnumber;
		String name;
		Set<String> forksUsed = new HashSet<>();

		boolean firstThink = true;
		int meals;
		List<Long> waitTimes = new LinkedList<Long>();
		Random random = new Random();

		@Override 
		public void run() {
			
			String tname = Thread.currentThread().getName();
			Thread.currentThread().setName(name);
			try {
				tableReady.countDown();
				log.info(name + " at table, forks used for eating: " + forksUsed);
				tableReady.await(1_000L, TimeUnit.MILLISECONDS);
				while (!stop) {
					think();
					waiters.incrementAndGet();
					if (log.isDebugEnabled()) {
						log.debug(name + " waiting " + waiters.get());
					}
					boolean haveLock = false;
					int failedLockCount = 0;
					long waitTime = System.currentTimeMillis();
					while (!haveLock) {
						try {
							forks.lock(forksUsed, MAX_WAIT_TIME_MS, TimeUnit.MILLISECONDS);
							haveLock = true;
						} catch (TimeoutException e) {
							failedLockCount++;
							if (log.isDebugEnabled()) {
								log.debug(name + " did not acquire forks within " + MAX_WAIT_TIME_MS + " ms. in attempt " + failedLockCount);
							}
						} catch (IllegalStateException e) {
							// Catch race conditions, these should not happen.
							// See also the comments in NamedResPool.waitForLock
							failedLockCount++;
							log.error("Pool inconsistent! Failed lock count: " + failedLockCount, e);
							forks.unlock();
						}
					}
					waitTimes.add(System.currentTimeMillis() - waitTime);
					waiters.decrementAndGet();
					if (failedLockCount > 0 && log.isDebugEnabled()) {
						log.debug(name + " got forks after " + (failedLockCount + 1) + " attempts");
					}
					try {
						eat();
					} finally {
						forks.unlock();
					}
				}
			} catch (Exception e) {
				log.error(name + " rudely removed from table.", e);
			} finally {
				tableEmpty.countDown();
				Thread.currentThread().setName(tname);
			}
		}
		
		void think() throws InterruptedException {
			
			thinkers.incrementAndGet();
			if (log.isDebugEnabled()) {
				log.debug(name + " thinking " + thinkers.get());
			}
			if (THINK_TIME_MS > 0L && !stop) {
				Thread.sleep(THINK_TIME_MS);
			}
			if (firstThink) {
				if (ORDERED_START) {
					// simulate all philosophers picking up forks one by one
					Thread.sleep(pnumber);
				}
				firstThink = false;
			}
			thinkers.decrementAndGet();
		}
		
		void eat() throws InterruptedException {
			
			eaters.incrementAndGet();
			if (EAT_TIME_MIN_MS < 1L && log.isDebugEnabled()) {
				log.debug(name + " eating " + eaters.get());
			}
			if (EAT_TIME_MIN_MS > 0L && !stop) {
				long eatTime = EAT_TIME_MIN_MS + (EAT_TIME_RANDOM_MS > 0 ? random.nextInt(EAT_TIME_RANDOM_MS) : 0);
				if (log.isDebugEnabled()) {
					log.debug(name + " eating " + eaters.get() + " for "+ eatTime + " ms. with " + forksUsed);
				}
				Thread.sleep(eatTime);
			}
			meals++;
			eaters.decrementAndGet();
		}
		
		String getStats() {
			
			long waitTimeLow = Long.MAX_VALUE;
			long waitTimeHigh = Long.MIN_VALUE;
			long totalWaitTime = 0L;
			
			for (Long waitTime : waitTimes) {
				if (waitTime < waitTimeLow) {
					waitTimeLow = waitTime;
				}
				if (waitTime > waitTimeHigh) {
					waitTimeHigh = waitTime;
				}
				totalWaitTime += waitTime;
			}
			String stats = "meals = " + meals;
			if (waitTimes.size() > 0) {
				stats += ", wait time avg / min / max / total - " 
						+ (totalWaitTime / waitTimes.size()) 
						+ " / " + waitTimeLow + " / " + waitTimeHigh + " / " + totalWaitTime;
			}
			return stats;
		}
	}
}
