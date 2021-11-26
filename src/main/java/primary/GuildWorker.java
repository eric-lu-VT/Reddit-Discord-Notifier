package primary;

/**
 * Repeatedly runs the script for a given guild, until it is told to stop.
 * @author @eric-lu-VT (Eric Lu)
 */

public class GuildWorker extends Thread {
    private String guildId; // unique ID of guild
    boolean active;         // true if the script should be running; false otherwise

    /**
     * primary.GuildWorker constructor
     * @param guildId unique ID of guild
     */
    public GuildWorker(String guildId) {
        this.guildId = guildId;
        active = true;
    }

    /**
     * Repeatedly runs the script, until it is told to stop.
     */
    @Override
    public void run() {
        while(active) {
            try {
                Bot.getSemaphore().updateReddit(guildId);
                sleep(30000); // 30 seconds
            }
            catch(InterruptedException e) {
                System.err.println(e);
            }
        }
    }

    /**
     * Tells the thread to stop running the script.
     */
    public void stopActive() {
        active = false;
    }
}