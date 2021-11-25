import java.util.Timer;

public class GuildWorker extends Thread {
    private String guildId;
    private UpdateDB semaphore;
    Timer timer;
    boolean active;

    public GuildWorker(String guildId, UpdateDB semaphore) {
        this.guildId = guildId;
        this.semaphore = semaphore;
        timer = new Timer();
        active = true;
    }

    @Override
    public void run() {
        while(active) {
            try {
                semaphore.updateReddit(guildId);
                sleep(30000); // 30 seconds
            }
            catch(InterruptedException e) {
                System.err.println(e);
            }
        }
        timer.cancel();
        timer.purge();
    }

    public void stopActive() {
        System.out.println("Stopping " + guildId);
        active = false;
    }
}