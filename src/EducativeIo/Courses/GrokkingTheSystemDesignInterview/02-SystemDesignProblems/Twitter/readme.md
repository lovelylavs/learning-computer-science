# Designing Twitter

1. [What is Twitter](#what-is-twitter)
2. [Requirements and Goals of the System](#requirements-and-goals-of-the-system)
3. [Capacity Estimation and Constraints](#capacity-estimation-and-constraints)
    1. [Storage Estimates](#storage-estimates)
    2. [Bandwidth Estimates](#bandwidth-estimates)
4. [System APIs](#system-apis)
5. [High Level System Design](#high-level-system-design)
6. [Database Schema](#database-schema)
    1. [Tables](#tables)
        1. [Tweet](#tweet)
        2. [User](#user)
        3. [UserFollow](#userfollow)
        4. [Favorite](#favorite)
    2. [Type of Database](#type-of-database)
7. [Data Sharding](#data-sharding)
    1. [Sharding based on UserID](#sharding-based-on-userid)
    2. [Sharding based on TweetID](#sharding-based-on-tweetid)
    3. [Sharding based on Tweet creation time](#sharding-based-on-tweet-creation-time)
    4. [Sharding based on TweetID and Tweet creation time](#sharding-based-on-tweetid-and-tweet-creation-time)
8. [Cache](#cache)
9. [Detailed component design diagram](#detailed-component-design-diagram)
10. [Timeline Generation](#timeline-generation)
11. [Replication and Fault Tolerance](#replication-and-fault-tolerance)
12. [Load Balancing](#load-balancing)
13. [Monitoring](#monitoring)
14. [Extended Requirements](#extended-requirements)
    1. [Serving feeds](#serving-feeds)
    2. [Retweet](#retweet)
    3. [Trending Topics](#trending-topics)
    4. [Who to follow? How to give suggestions](#who-to-follow-how-to-give-suggestions)
    5. [Moments](#moments)
    6. [Search](#search)

Design a Twitter-like social networking service. Users of the service will be able to post tweets, follow other people, and favorite tweets.

## What is Twitter

## Requirements and Goals of the System

- Functional Requirements
  1. Users should be able to post new tweets.
  2. A user should be able to follow other users.
  3. Users should be able to mark tweets as favorites.
  4. The service should be able to create and display a user’s timeline consisting of top tweets from all the people the user follows.
  5. Tweets can contain photos and videos.
- Non-Functional Requirements
  1. Our service needs to be highly available.
  2. Acceptable latency of the system is 200ms for timeline generation.
  3. Consistency can take a hit (in the interest of availability); if a user doesn't see a tweet for a while, it should be fine.
- Extended Requirements and Goals
  1. Searching for tweets.
  2. Replying to a tweet.
  3. Trending topics – current hot topics/searches.
  4. Tagging other users.
  5. Tweet Notification.
  6. Who to follow? Suggestions?
  7. Moments.

## Capacity Estimation and Constraints

- 1 B total users with 200 M DAU.
- 100 M new tweets every day.
- On average each user follows 200 people.
- Favorites per day
  - Say, each user favorites 5 tweets per day.
  - `200 M users * 5 favorites = 1 B`
- Total tweet-views generated by system
  - Say, user visits timeline twice a day, and visits 5 other people's pages.
  - On each page say user sees 20 tweets
  - Total tweet-views = `200 M DAU * ((2+5)* 20 tweets) = 28 B /day`

### Storage Estimates

```plaintext
Each tweet has 140 characters.
To store each character without compression we need 2 bytes.
Metadata for each tweet (ID, timestamp,userID, etc.), say we need 30 bytes.
Total storage needed = 100 M tweets/day * (280 + 30) bytes = 30 GB/day

Say, on average every fifth tweet has a photo and every tenth tweet has a video.
Say, on average a photo is 200 KB and a video is 2 MB.
Storage needed for new media every day
  = (100M/5 photos * 200 KB) + (100M/10 videos * 2 MB) ~= 24 TB/day
```

### Bandwidth Estimates

```plaintext
Total ingress =  24 TB/day = 290 MB/sec
Tweet-views per day = 28 B
Say, users will only watch 3rd video they see in their timeline
Total egress
  = (28 B * 280 bytes) / 86400s of text => 93 MB/sec
    + (28B/5 * 200 KB) / 86400s of photos => 13 GB/sec
    + (28B/10/3 * 2 MB) / 86400s of videos => 22 GB/sec
  ~= 35 GB/sec
```

## System APIs

```plaintext
tweet(api_dev_key, tweet_data, tweet_location, user_location,
  media_ids, maximum_results_to_return)

Returns URL to access the tweet.
```

## High Level System Design

- Need to store 100M/86400s = 1150 tweets/sec and read 28B/86400s = 325K tweets/sec.
- Read heavy system.
- This traffic will be distributed unevenly throughout the day, though, at peak time we should expect at least a few thousand write requests and around 1M read requests per second.

[high level design](./images/high-level-design_base64.md)

## Database Schema

### Tables

#### Tweet

| Column         | Type         |
| -------------- | ------------ |
| TweetID (PK)   | int          |
| UserID         | int          |
| Content        | varchar(140) |
| TweetLatitude  | int          |
| TweetLongitude | int          |
| UserLatitude   | int          |
| UserLongitude  | int          |
| CreationDate   | datetime     |
| NumFavorites   | int          |

#### User

| Column       | Type        |
| ------------ | ----------- |
| UserID (PK)  | int         |
| Name         | varchar(20) |
| Email        | varchar(32) |
| DateOfBirth  | datetime    |
| CreationDate | datetime    |
| LastLogin    | datetime    |

#### UserFollow

| Column       | Type |
| ------------ | ---- |
| UserID1 (PK) | int  |
| UserID2 (PK) | int  |

#### Favorite

| Column       | Type     |
| ------------ | -------- |
| TweetID (PK) | int      |
| UserID (PK)  | int      |
| CreationDate | datetime |

### Type of Database

- Obvious choice is to use an RDBMS d/b, but scaling can be difficult.
- For scaling advantage of NoSQL, a distributed K-V store can be used.
  - Metadata related to photos can go to a table where key would be PhotoID and value would be an object containing rest of the items.
- Column-wide datastore like Cassandra can be used for
  - Storing relationships between users and photos.
    - UserPhoto table: key would be UserID, value would be a list of PhotoIDs the user owns, stored in different columns.
  - List of people a user follows.
    - UserFollow table: Similar as for UserPhoto table.
- K-V stores maintain replicas for reliability. Also data is marked as deleted before getting deleted permanently.
- Photos can be stored on a distributed file storage like HDFS or S3, or Azure blobs.

## Data Sharding

- Read load is extremely high.

### Sharding based on UserID

- All data for a user on one server.
- Hash function uses UserID to map user to d/b server which stores all user's tweets, favorites, follows, etc.
- Issues
  - Could lead to hot-spots, if a user becomes hot.
  - Maintaining uniform distribution of growing user data is difficult.
- Solution
  - Repartition/redistribute data, or use consistent hashing.

### Sharding based on TweetID

- Hash function will map TweetID to a server where tweet will be stored.
- Timeline generation workflow
  1. Our application (app) server will find all the people the user follows.
  2. App server will send the query to all database servers to find tweets from these people.
  3. Each database server will find the tweets for each user, sort them by recency and return the top tweets.
  4. App server will merge all the results and sort them again to return the top results to the user.
- Issues
  - High latencies due to having to query multiple d/b partitions and do aggregation.
- Solution
  - Cache to store hot tweets in front of database servers.

### Sharding based on Tweet creation time

- Will be able to fetch all top tweets quickly.
- Will have to query only a very small set of servers.
- Issues
  - Traffic load will not be distributed.
  - While writing, new tweets will go to one server, while remaining servers will be idle.
  - While reading, server holding latest data will have high load.

### Sharding based on TweetID and Tweet creation time

- Make TweetID universally unique and each TweetID will contain a timestamp.
- Using epoch time.
- TweetID will have 2 parts.
  - First part will be representing epoch seconds.
  - Second part will be an auto-incrementing sequence.
- We can take the current epoch time and append an auto-incrementing number to it.
- We can figure out the shard number from this TweetID and store it there.
- Size of TweetID
  - How many bits to store number of seconds for next 50 years
    - `86400 sec/day * 365 days * 50 years = 1.6 B`
    - Need 31 bits to store this number. (2^31 ~= 2 B)
  - 1150 tweets/sec, so we can allocate 17 bits to store auto incremented sequence.
  - So every second we can store 2^17 = 130K new tweets.
  - 31 bits for epoch seconds; 17 bits for auto incrementing sequence.
- We can reset auto incrementing sequence every second.
- For resilience and performance we can have two database servers generating auto-incrementing keys, one generating even numbered and other odd numbered keys.
- If current epoch seconds is "1483228800", TweetID will look like this:
  - 1483228800 000001
  - 1483228800 000002
  - 1483228800 000003
  - 1483228800 000004
  - …
- If we make our TweetID 64bits (8 bytes) long, we can easily store tweets for the next 100 years and also store them for milliseconds granularity.
- While we will still have to query multiple servers for timeline generation, reads and writes will be lot quicker
  - Since there is no secondary index (on creation time) this will reduce write latency.
  - While reading, we do not need to filter on creation-time as the primary key would have epoch time included in it.

## Cache

- Cache for database servers to cache hot tweets and users.
- Memcache for storing whole tweet objects.
- Based on client usage patterns we can determine how many cache servers we need.
- Cache replacement policy: LRU
- More intelligent cache
  - 80-20 rule
    - 20% of tweets generating 80% of read traffic.
    - Cache 20% of daily read volume from each shard.
- Caching latest data?
  - Dedicated cache server for caching tweets for past 3 days.
  - 100 M new tweets or 30 GB of data each day (not including media).
  - This would need 100 Gb of memory.
  - Single server with replication to multiple servers for read distribution.
  - Same design can be used for photos and videos for last 3 days.
  - Cache would be a hash table, key would be OwnerID and value would be a doubly linked list containing all tweets from that user in last 3 days.
  - New tweets will be inserter at the head of the linked list and older tweets will be near the tail. Tweets can be removed from the tail to make space for newer tweets.

## Detailed component design diagram

[detailed component design](./images/detailed-component-design_base64.md)

## Timeline Generation

Check Designing Facebook's Newsfeed problem.

## Replication and Fault Tolerance

- Since system is read-heavy, need multiple secondary servers for each d/b partition.
- Secondary servers for read traffic only.
- All writes will go to primary server and then get replicated to secondaries.
- This mechanism will also provide fault tolerance since when the primary server goes down, failover to a secondary can happen.

## Load Balancing

1. Between Clients and Application servers.
2. Between Application servers and database replication servers.
3. Between Aggregation servers and Cache server.

- Simple round robin approach to begin with.
  - Advantage
    - Easy to implement.
    - If a server is dead, LB will take it out of the rotation and stop send it traffic.
  - Issues
    - Does not take server load in account.
- To handle issue, more intelligent LB solution can be used that periodically queries backend server about load and accordingly adjusts traffic.

## Monitoring

Performance metrics/counters that can be collected:

1. New tweets per day/second, what is the daily peak?
2. Timeline delivery stats, how many tweets per day/second our service is delivering.
3. Average latency that is seen by the user to refresh timeline.

This data will help determine configuration variables for replication, load balancing, caching, etc.

## Extended Requirements

### Serving feeds

- Approach 1
  - Get latest N tweets from people someone follows and merge/sort by time.
  - N depends on client's viewport.
  - Next top tweets can be cached to speed things up.
  - Pagination to display tweets.
- Approach 2 (same as Ranking and timeline generation under "Designing Instagram" problem)
  - Pre-generating the News Feed
    - Servers that generate users' News Feeds and store them in a 'UserNewsFeed' table.
    - Servers will check last time News Feed was generated by looking at 'UserNewsFeed' table. New News feed will be generated from that time onwards.
  - Approaches for sending NewsFeed contents to users
    - Pull
      - Clients pull news feed data periodically or on-demand.
      - Issues
        - New data may not be shown until a new client side request comes in.
        - New pull requests may often result in empty response if there is no data.
    - Push
      - Servers push new data to users when it is available.
      - Users maintain Long Poll request to server.
      - Issues
        - User who follows lot of people, or for user who has millions of followers, server has to push frequent updates.
    - Hybrid
      - Approach 1
        - Pull model for users with high follows.
        - Push model for users with few(er) follows.
      - Approach 2
        - Server pushes updates to all users at certain max frequency.
        - Users wil lot of follows/updates pull data.

### Retweet

For every retweet, with each Tweet object in the d/b we can store the ID of the original Tweet.

### Trending Topics

- Cache most frequently occurring hashtags or search queries in last N seconds and keep updating them every M seconds.
- Rank trending topics based on frequency of tweets, search queries, retweets or likes.
- Can give more weight to topics which are shown to more people.

### Who to follow? How to give suggestions

- This feature will improve user engagement.
- We can suggestion friends of people someone follows.
- We can go two or three levels down to find famous people for the suggestions.
- We can give preference to people with more followers.
- ML algorithms can be used based on various weights.

### Moments

- Top news for different websites for past 1, 2 hours, figure out tweets, prioritize them, categorize them using ML algorithms - supervised learning or clustering.
- These articles can then be shown as trending topics in Moments.

### Search

- Involves indexing, ranking, retrieval of tweets.
- Discussed in detail in Designing Twitter Search problem.
