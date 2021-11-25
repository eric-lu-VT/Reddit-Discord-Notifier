import java.util.*;

public class ServerPostSchema {
    class Query {
        private String query;
        private String subreddit;

        public Query(String query, String subreddit) {
            this.query = query;
            this.subreddit = subreddit;
        }

        public String getQuery() {
            return query;
        }

        public String getSubreddit() {
            return subreddit;
        }
    }

    private String _id;
    private List<String> channels;
    private List<Query> queries;

    public ServerPostSchema() {

    }
}
