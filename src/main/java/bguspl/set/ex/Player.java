package bguspl.set.ex;

import static java.util.Collections.synchronizedCollection;

import java.util.Random;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;

import bguspl.set.Env;

/**
 * This class manages the players' threads and data
 *
 * @inv id >= 0
 * @inv score >= 0
 */
public class Player implements Runnable {

    /**
     * The game environment object.
     */
    private final Env env;

    /**
     * Game entities.
     */
    private final Table table;

    /**
     * The id of the player (starting from 0).
     */
    public final int id;

    /**
     * The thread representing the current player.
     */
    private Thread playerThread;

    /**
     * The thread of the AI (computer) player (an additional thread used to generate key presses).
     */
    private Thread aiThread;

    /**
     * True iff the player is human (not a computer player).
     */
    private final boolean human;

    /**
     * True iff game should be terminated.
     */
    private volatile boolean terminate;

    /**
     * The current score of the player.
     */
    private int score;

    /**
     * New Fields we added
     */
    protected ArrayBlockingQueue<Integer> actionsQueue; // contains the player's input
    protected int[] tokens;
    private Dealer dealer;
    protected AtomicBoolean point = new AtomicBoolean(false);
    protected AtomicBoolean penalty = new AtomicBoolean(false);
    protected AtomicBoolean afterPen = new AtomicBoolean(false); //wheter the player is after penalty or not
    protected AtomicBoolean run = new AtomicBoolean(true); //boolean representing whtehr the thread should rest or keep running (local for every player)


     /**
     * @param env
     * @param dealer
     * @param table
     * @param id
     * @param human
     */

    /**
     * The class constructor.
     *
     * @param env    - the environment object.
     * @param dealer - the dealer object.
     * @param table  - the table object.
     * @param id     - the id of the player.
     * @param human  - true iff the player is a human player (i.e. input is provided manually, via the keyboard).
     */
    public Player(Env env, Dealer dealer, Table table, int id, boolean human) {
        this.env = env;
        this.table = table;
        this.id = id;
        this.human = human;
        this.dealer = dealer;
        this.score = 0;
        actionsQueue = new ArrayBlockingQueue<Integer>(env.config.featureSize); //a blocking queue contains all the current actions -chosen cards for specific set, of a player's thread
        tokens = new int[env.config.featureSize + 1];
        tokens[env.config.featureSize] = id;
    }

    /**
     * The main player thread of each player starts here (main loop for the player thread).
     */
    @Override
    public void run() {
        playerThread = Thread.currentThread();
        env.logger.info("thread " + Thread.currentThread().getName() + " starting.");
        if (!human) createArtificialIntelligence();

        while (!terminate) {
            // TODO implement main player loop
            try {
                act();
            } catch (InterruptedException e) {}
        }
        if (!human) {
            try {
                 aiThread.join(); //as the AI thread finish its running (exiting the while loop after terminate method is being called from the dealer) it's being terminated.
            } catch (InterruptedException ignored) {}
        }
        env.logger.info("thread " + Thread.currentThread().getName() + " terminated.");
    }

    /**
     * Creates an additional thread for an AI (computer) player. The main loop of this thread repeatedly generates
     * key presses. If the queue of key presses is full, the thread waits until it is not full.
     */
    private void createArtificialIntelligence() {
        // note: this is a very, very smart AI (!) - we wouldn't say...
        aiThread = new Thread(() -> {
            env.logger.info("thread " + Thread.currentThread().getName() + " starting.");
            while (!terminate) {
                // TODO implement player key press simulator
                Random rand = new Random();
                int randSlot = rand.nextInt(env.config.tableSize);
                keyPressed(randSlot);
            }
            env.logger.info("thread " + Thread.currentThread().getName() + " terminated.");
        }, "computer-" + id);
        aiThread.start();
    }

    /**
     * Called when the game should be terminated.
     */
    public void terminate() {
        // TODO implement
        terminate = true;
        playerThread.interrupt();
        System.out.println("terminating " + id);
        
        try {
            playerThread.join();
        } catch (InterruptedException ignored) {}
    }

    /**
     * This method is called when a key is pressed.
     *
     * @param slot - the slot corresponding to the key pressed.
     */
    public void keyPressed(int slot) {
        // TODO implement
            if ((!penalty.get() && !point.get() && !terminate) & !table.tableLock.get()) { //if the table isn't locked by the dealer thread (for changing cards and positions) let the players add actions (legal ones)
                try {
                    actionsQueue.put(slot);
                } catch (InterruptedException e) {}
            }
    }

    //New Method
    private void act() throws InterruptedException {
        if (!table.tableLock.get() && !terminate) {
            int slot = actionsQueue.take();
            if (table.slotToCard[slot] != null) { 
                if (table.removeToken(id, slot)) { //the case where player want to cancel it's action (can happen just before dealer's check)
                    afterPen.set(false);
                }
                else {
                    table.placeToken(id, slot);
                    if (!afterPen.get()) {
                        boolean set = true;
                        for (int i = 0; i < env.config.featureSize; i++) {
                            if (table.playersTokens[id][i] == -1) {
                                set = false;
                                break;
                            }
                        }
                        if (set) {
                            checkSet();
                        }
                    }
                    
                }       
            }
        }   
    }

    /**
     * Award a point to a player and perform other related actions.
     *
     * @post - the player's score is increased by 1.
     * @post - the player's score is updated in the ui.
     */
    public synchronized void point() { // Added synchronized
        // TODO implement
        
        int ignored = table.countCards(); // this part is just for demonstration in the unit tests
        long pntTime = env.config.pointFreezeMillis;
        env.ui.setScore(id, ++score);
        try{
            while (pntTime > 0) {
                env.ui.setFreeze(id, pntTime);
                Thread.sleep(1000);
                pntTime -= 1000;
                if (pntTime <= 0){
                    env.ui.setFreeze(id, 0);
                }
            }
        } catch (InterruptedException e) {}
        point.set(false);
    }

    /**
     * Penalize a player and perform other related actions.
     */
    public synchronized void penalty() { // Added synchronized
        // TODO implement
        long penTime = env.config.penaltyFreezeMillis;
        afterPen.set(true); //AtomicBoolean represnting whether the player is in just after penalty or not
        try{
            while (penTime > 0){
                env.ui.setFreeze(id, penTime);
                Thread.sleep(1000);
                penTime -= 1000;
                if (penTime <= 0) {
                    env.ui.setFreeze(id, 0);
                }
            }
        } catch (InterruptedException ignored) {}
        penalty.set(false);
    }

    public int score() {
        return score;
    }

    // New methods we added
    public synchronized void checkSet() throws InterruptedException {
        run.set(false); //while the set is under check, let the player thread rest (wait..)
        dealer.lock.acquire();
        int[] set = new int[env.config.featureSize + 1];
        for (int i = 0; i < env.config.featureSize; i++) {
            if (table.playersTokens[id][i] == -1) {
                dealer.lock.release();
                run.set(true);
                return;
            }
            set[i] = table.slotToCard[table.playersTokens[id][i]]; //the player's set (by chosen cards)
        }
        set[env.config.featureSize] = id;
        dealer.checkSets.put(set); //push the set into the dealer check list 
        dealer.lock.release();
        synchronized (dealer) {
            dealer.notifyAll(); //notify the delar he has a job
        }
        if (!terminate) {
            this.wait();
            if (point.get()) {
                point();
            }
            else if (penalty.get()) {
                penalty();
            }
            run.set(true);
        }
    }

    // New Method
    public void clearActionsQueue() {
        actionsQueue.clear();
        afterPen.set(false);
        run.set(true);
        synchronized (this) {   
            this.notifyAll();
        }
    }
}
