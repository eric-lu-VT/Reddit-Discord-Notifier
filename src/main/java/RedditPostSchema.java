import java.util.*;

public class RedditPostSchema {
    private String postid;
    private String subreddit;
    private String url;
    private String date;
    private String guildId;
    private Date createdAt;
    private Date expireAt;

    public RedditPostSchema(String postid, String subreddit, String url, String date,
                            String guildId, Date createdAt, Date expireAt) {
        this.postid = postid;
        this.subreddit = subreddit;
        this.url = url;
        this.date = date;
        this.guildId = guildId;
        this.createdAt = createdAt;
        this.expireAt = expireAt;
    }

    public String getPostid() {
        return postid;
    }

    public String getSubreddit() {
        return subreddit;
    }

    public String getUrl() {
        return url;
    }

    public String getDate() {
        return date;
    }

    public String getGuildId() {
        return guildId;
    }

    public Date getCreatedAt() {
        return createdAt;
    }

    public Date getExpireAt() {
        return expireAt;
    }
}
