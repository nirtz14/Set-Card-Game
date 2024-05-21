package bguspl.set.ex;

import bguspl.set.Env;

//import java.util.List;
import java.util.*;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Semaphore;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static java.util.Collections.shuffle;

/**
 * This class manages the dealer's threads and data
 */
public class Dealer implements Runnable {

    /**
     * The game environment object.
     */
    private final Env env;

    /**
     * Game entities.
     */
    private final Table table;
    private final Player[] players;
    protected Semaphore lock;
    ArrayBlockingQueue<int[]> checkSets;

    /**
     * The list of card ids that are left in the dealer's deck.
     */
    private final List<Integer> deck;

    /**
     * True iff game should be terminated.
     */
    private volatile boolean terminate;

    /**
     * The time when the dealer needs to reshuffle the deck due to turn timeout.
     */
    private long reshuffleTime = Long.MAX_VALUE;

    public Dealer(Env env, Table table, Player[] players) {
        this.env = env;
        this.table = table;
        this.players = players;
        deck = IntStream.range(0, env.config.deckSize).boxed().collect(Collectors.toList());
        lock = new Semaphore(1, true);  
        checkSets = new ArrayBlockingQueue<int[]>(players.length, true);
        terminate = false;
    }

    /**
     * The dealer thread starts here (main loop for the dealer thread).
     */
    @Override
    public void run() {
        startPlayers(); //initializing all the players threads
        shuffle(deck);
        env.logger.info("thread " + Thread.currentThread().getName() + " starting.");
        while (!shouldFinish()) {
            placeCardsOnTable();
            timerLoop();
            updateTimerDisplay(false);
            removeAllCardsFromTable();
        }
        announceWinners();
        terminate();
        env.logger.info("thread " + Thread.currentThread().getName() + " terminated.");
    }

    /**
     * The inner loop of the dealer thread that runs as long as the countdown did not time out.
     */
    private void timerLoop() {
        while (!terminate && System.currentTimeMillis() < reshuffleTime) {
            sleepUntilWokenOrTimeout();
            updateTimerDisplay(false);
            removeCardsFromTable();
            placeCardsOnTable();
        }
    }

    /**
     * Called when the game should be terminated.
     */
    public void terminate() {
        // TODO implement
        terminate = true;
        for (int i = players.length - 1; i >= 0; i--) {
            System.out.println("trying to terminate player: " + i);
            players[i].terminate();
            System.out.println("terminated player: " + i);
        }
        System.out.println("terminated all players");
    }

    /**
     * Check if the game should be terminated or the game end conditions are met.
     *
     * @return true iff the game should be finished.
     */
    private boolean shouldFinish() {
        return terminate || env.util.findSets(deck, 1).size() == 0;
    }

    /**
     * Checks cards should be removed from the table and removes them.
     */
    private void removeCardsFromTable() {
        // TODO implement
        try{
            if (!checkSets.isEmpty() && reshuffleTime >= System.currentTimeMillis()) { //if the dealer has sets to check and still has some time for the round
                while (!table.tableLock.compareAndSet(false, true)) {} //makes sure the table is getting lock so the dealer can make changes in it 
                int[] split = checkSets.take();
                boolean valid = true;
                int[] set = new int[env.config.featureSize]; 
                for (int i = 0; i < env.config.featureSize; i++) {
                    if (table.cardToSlot[split[i]] != null) {
                        set[i] = split[i]; 
                    }
                    else {
                        valid = false; //if the set, as for that point, includes a card that was already has been removed 
                        break;
                    }
                }
                if (!valid) {
                    table.tableLock.compareAndSet(true, false);
                    synchronized(players[split[env.config.featureSize]]) {
                        players[split[env.config.featureSize]].notifyAll(); //a player whose one of it's set's cards already been removed, now its not part of its's actionsQueue 
                    }
                    return;
                }
                if (env.util.testSet(set)) {
                    for (int i = 0; i < env.config.featureSize; i++) {
                        //table.removeSlotTokens(table.cardToSlot[split[i]]);
                        for(Player player : players){ //for all the other players whom might have tokens on the set that has just been checked and about to been remove from table
                            if (player.id != split[env.config.featureSize]){
                                if(player.actionsQueue.contains(table.cardToSlot[split[i]])){
                                    player.actionsQueue.remove(table.cardToSlot[split[i]]); //if they put a token on a card of an approved set, the action is being removed from their actionsQueue
                                }
                            }
                        }    
                        table.removeCard(table.cardToSlot[split[i]]);
                    }
                    shuffle(table.openSlots);
                    while (!players[split[env.config.featureSize]].point.compareAndSet(false, true)) {}
                    if (deck.isEmpty() && table.countCards() == 0) {
                        terminate = true;
                    }
                }
                else {
                    while (!players[split[env.config.featureSize]].penalty.compareAndSet(false, true)) {}
                }
                table.tableLock.compareAndSet(true, false);
                synchronized(players[split[env.config.featureSize]]) {
                    players[split[env.config.featureSize]].notifyAll();
                }
            }
        } catch (InterruptedException ignored) {}
    }

    /**
     * Check if any cards can be removed from the deck and placed on the table.
     */
    private void placeCardsOnTable() {
        // TODO implement
        if (!deck.isEmpty() && !table.openSlots.isEmpty()) {
            while (!table.tableLock.compareAndSet(false, true)) {} //while the dealer is placing new cards we donwt want any player to interrupt
            while (!table.openSlots.isEmpty() && !deck.isEmpty()) {                
                table.placeCard(deck.remove(0), table.openSlots.removeFirst());
            }
            updateTimerDisplay(true);
            if (env.config.hints) { // Bonus feature
                table.hints();
            }
            table.tableLock.compareAndSet(true, false);
        }
    }

    /**
     * Sleep for a fixed amount of time or until the thread is awakened for some purpose.
     */
    private synchronized void sleepUntilWokenOrTimeout() {
        // TODO implement
        if (checkSets.isEmpty()){
            if (reshuffleTime - System.currentTimeMillis() <= env.config.turnTimeoutWarningMillis) {
                try {
                    wait (10); //in this case we want the dealer to be ready for the next action to come (just before the end of the round)
                } catch (InterruptedException e) {}
            }
            else {
                try {
                    wait(1000);
                } catch (InterruptedException e) {}
            }
        }
    }

    /**
     * Reset and/or update the countdown and the countdown display.
     */
    private void updateTimerDisplay(boolean reset) {
        // TODO implement
        if (reset){
            this.reshuffleTime = System.currentTimeMillis() + env.config.turnTimeoutMillis + 999;
            env.ui.setCountdown(env.config.turnTimeoutMillis, false);
        }
        else {
            if (reshuffleTime - System.currentTimeMillis() <= 0) {
                env.ui.setCountdown(0, true);
            }
            else if (reshuffleTime - System.currentTimeMillis() <= env.config.turnTimeoutWarningMillis) {
                env.ui.setCountdown(reshuffleTime - System.currentTimeMillis(), true);
            }
            else {
                env.ui.setCountdown(reshuffleTime - System.currentTimeMillis(), false);
            }
        }
    }

    /**
     * Returns all the cards from the table to the deck.
     */
    private void removeAllCardsFromTable() {
        // TODO implement
        while (!table.tableLock.compareAndSet(false, true)) {}
        table.removeAllTokens(); //in order to make sure all the tokens has been removed
        //table.removeAllTokens();
        for (int i = 0; i < table.slotToCard.length; i++){
            if (table.slotToCard[i] != null){
                deck.add(table.slotToCard[i]); //taking the cards that hasn't been removed in this round, back to the deck
                table.removeCard(i);
            }
        }
        shuffle(table.openSlots);
        checkSets.clear(); //making sure no prior set is staying in the dealer's sets to check
        for (Player player : players){
                player.clearActionsQueue(); //or the player's actionsQueue
        }
        table.tableLock.compareAndSet(true, false);
        shuffle(deck);
    }

    /**
     * Check who is/are the winner/s and displays them.
     */
    private void announceWinners() {
        // TODO implement
        int maxpts = 0;
        LinkedList<Integer> winners = new LinkedList<Integer>();
        for (Player p : players) {
            if (p.score() > maxpts){
                maxpts = p.score();
            }
        }
        for (Player p : players){
            if (p.score() == maxpts){
                winners.add(p.id);
            }
        }
        int[] ret = new int [winners.size()];
        for (int i = 0; i < ret.length; i++){
            ret[i] = winners.removeFirst();
        }
        env.ui.announceWinner(ret);
    }

    //New Method
    private void startPlayers() {
        Thread[] threads = new Thread[players.length];
        for (int i = 0; i < players.length; i++) {
            threads[i] = new Thread(players[i]);
        }
        for (int i = 0; i < players.length; i++) {
            threads[i].start();
            System.out.println("Starting player - " + i);
        }
    }
}
