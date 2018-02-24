# Named Resources Pool #

The named resources pool in this small project solves the "dining philosophers problem" ([wiki](https://en.wikipedia.org/wiki/Dining_philosophers_problem)) using a 'critical section' as outlined by Martin James in [this](https://stackoverflow.com/a/19316257) stackoverflow answer.
To see it in action, checkout this project and run `src/test/java/nl/fw/util/DiningPhilosophers.java`. Update the variables at the top of the class and see what happens if 50 philosophers each use 7 forks. Watch the difference in meals per philosopher before and after `MAX_WAIT_TIME_MS` is set to -1.

Named resources are resources of the same type but with different identities, e.g. all resources are spoons (or forks or chopsticks) but each spoon has a number. If there are 5 spoons and 3 users each requesting to use certain spoons at the same time, deadlocks and starvation can occur.
For example:
 * user A requests spoons 1 and 2
 * user B requests spoons 2 and 3
 * &rarr; both users can deadlock on waiting for spoon 2. 

With a critical section this deadlock will never happen, one request always goes after another.
Another example:
 * user C is using spoon 2 (i.e. spoon 2 is locked)
 * user A requests spoons 1, 2 and 3
 * user B requests spoons 3 and 4
 * &rarr; spoons 3 and 4 are available but user B is waiting on user A which is waiting on user C to release spoon 2

With a critical section it is possible to allow the requests from user B and lock spoons 3 and 4 because they are readily available.
This will 'starve' user A though: user A's requests are skipped in favor of user B's requests.
The Named Resources Pool solves this by increasing the priority for the requests from user A. 
The next time user B (or another user) requests spoons 1 and/or 3, 
the request will be denied (i.e. user B will have to wait) because user A has a higher priority.  
You can see this mechanic in action with the debug-log statements from `NamedResPool` when running the `DiningPhilosophers` test, e.g.:
 * [P8] - Prioritizing lock from P7 (6) on F10
 * [P22] - Not prioritizing lock from P18 (3) over lock from P20 (5) on F20

There are limitations though, once a user has resources locked, it is not possible to lock additional resources. The latter can result in deadlocks (i.e. the critical section does not account for this use case).
For example, allowed:
 * user A locks spoons 1 and 3
 * user A releases spoons 1 and 3
 * user A locks spoons 1, 2 and 3
 * user A releases spoons 1, 2 and 3

In contrast, this can produce a deadlock:
 * user A locks spoons 1 and 2
 * user A requests a lock on spoon 3 
 * &rarr; the critical section may not prevent a deadlock on the request for spoon 3 because it assumes the lock on spoon 1 and 2 will be released at some time in the future.
 
The `NamedResPool` implementation has 2 tricks:
 * the current thread context can be used as a 'user'. The lock and unlock operations will create and remove this user on the fly.
 * named resources are added and removed from the pool as needed. This means named resources do not need to be known at forehand and named resources can be any amount, e.g. if parts of a huge linked list need to be locked, only the nodes that need to be locked become part of the pool. Another side effect is that it is easy to detect when something is wrong: after pool usage, the pool must be empty (no registered users or resources). If this is not the case, an `unlock` operation was skipped.
 
For more information:
 * examine the test-code in `src/test/java/nl/fw/util/DiningPhilosophers.java`
 * see the package-info in `nl.fw.util.namedrespool`
 * see the JavaDocs (commments) in  `nl.fw.util.namedrespool.NamedResPool`

On a side node, a simple resource pool for uniform resources that are managed by an external process is shown in [this](https://stackoverflow.com/a/34377106) stackoverflow answer.
 
### Status ###

The code is at a demo-stage.  
Despite my best effort, the critical section still does a lot of administration.  
A review is needed. Some sort of proof that no deadlocks can occur and that starvation is in check is needed. 
