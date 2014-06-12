package com.sematext.solr.redis;

import java.io.IOException;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.core.KeywordAnalyzer;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.util.BytesRef;
import org.apache.solr.common.params.SolrParams;
import org.apache.solr.request.SolrQueryRequest;
import org.apache.solr.search.QParser;
import org.apache.solr.search.QueryParsing;
import org.apache.solr.search.SyntaxError;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.Tuple;
import redis.clients.jedis.exceptions.JedisException;

/**
 * RedisQParser is responsible for preparing a query based on data fetched from Redis.
 */
public class RedisQParser extends QParser {
  private static final Logger log = LoggerFactory.getLogger(RedisQParserPlugin.class);

  private static final Set<String>  ALLOWED_METHODS = new HashSet<String>(){
      {
        add("smembers");
        add("zrevrangebyscore");
        add("zrangebyscore");
      }
  };

  private final JedisPool jedisPool;
  private Set<String> redisObjectsCollection = null;
  private Map<String, Float> scores = null;
  private BooleanClause.Occur operator = BooleanClause.Occur.SHOULD;
  private String redisMethod;
  private String redisKey;
  private boolean useQueryTimeAnalyzer;
  private int maxJedisRetries;

  RedisQParser(String qstr, SolrParams localParams, SolrParams params, SolrQueryRequest req, JedisPool jedisPool) {
    this(qstr, localParams, params, req, jedisPool, 0);
  }

  RedisQParser (String qstr, SolrParams localParams, SolrParams params, SolrQueryRequest req,
          JedisPool jedisPool, int maxJedisRetries) {
    super(qstr, localParams, params, req);
    this.jedisPool = jedisPool;

    redisMethod = localParams.get("method");
    redisKey = localParams.get("key");
    String operatorString = localParams.get("operator");
    String useAnalyzerParam = localParams.get("useAnalyzer");

    if (redisMethod == null) {
      log.error("No method argument passed to RedisQParser.");
      throw new IllegalArgumentException("No method argument passed to RedisQParser.");
    } else if (!ALLOWED_METHODS.contains(redisMethod)) {
      log.error("Wrong Redis method: {}", redisMethod);
      throw new IllegalArgumentException("Wrong Redis method.");
    }

    if (redisKey == null || redisKey.isEmpty()) {
      log.error("No key argument passed to RedisQParser");
      throw new IllegalArgumentException("No key argument passed to RedisQParser");
    }

    if (operatorString != null && "AND".equalsIgnoreCase(operatorString)) {
      operator = BooleanClause.Occur.MUST;
    } else {
      operator = BooleanClause.Occur.SHOULD;
    }

    if (useAnalyzerParam == null || Boolean.parseBoolean(useAnalyzerParam)) {
      useQueryTimeAnalyzer = true;
    } else {
      useQueryTimeAnalyzer = false;
    }
    this.maxJedisRetries = maxJedisRetries;
  }

  @Override
  public Query parse() throws SyntaxError {
    String fieldName = localParams.get(QueryParsing.V);
    BooleanQuery booleanQuery = new BooleanQuery(true);
    int booleanClausesTotal = 0;

    fetchDataFromRedis(redisMethod, redisKey, maxJedisRetries, params);

    if (redisObjectsCollection != null) {
      log.debug("Preparing a query for " + redisObjectsCollection.size() + " redis objects for field: " + fieldName);

      for (String termString : redisObjectsCollection) {
        try {
          TokenStream tokenStream = null;
          if (useQueryTimeAnalyzer) {
            tokenStream = req.getSchema().getQueryAnalyzer().tokenStream(fieldName, termString);
          } else {
            tokenStream = new KeywordAnalyzer().tokenStream(fieldName, termString);
          }

          BytesRef term = new BytesRef();
          if (tokenStream != null) {
            CharTermAttribute charAttribute = tokenStream.addAttribute(CharTermAttribute.class);
            tokenStream.reset();

            int counter = 0;
            while (tokenStream.incrementToken()) {

              log.trace("Taking {} token from query string from {} for field: {}",
                      ++counter, termString, fieldName);

              term = new BytesRef(charAttribute);
              TermQuery termQuery = new TermQuery(new Term(fieldName, term));
              if (scores != null && !scores.isEmpty()) {
                termQuery.setBoost(scores.containsKey(termString) ? scores.get(termString) : 1.0f);
              }
              booleanQuery.add(termQuery, this.operator);
              ++booleanClausesTotal;
            }

            tokenStream.end();
            tokenStream.close();
          } else {
            term.copyChars(termString);
            TermQuery termQuery = new TermQuery(new Term(fieldName, term));
            booleanQuery.add(termQuery, this.operator);
            ++booleanClausesTotal;
          }
        } catch (IOException ex) {
          log.error("Error occured during processing token stream.", ex);
        }
      }
    }

    log.debug("Prepared a query for field {} with {} boolean clauses", fieldName, booleanClausesTotal);

    return booleanQuery;
  }

  private void fetchSmembers(Jedis jedis, String redisKey, int maxJedisRetries) {
    log.debug("Fetching smembers from Redis for key: " + redisKey);
    redisObjectsCollection = jedis.smembers(redisKey);
  }

  private void fetchRevrangeByScore(Jedis jedis, String redisKey, int maxJedisRetries,
          SolrParams params) {
    String min = localParams.get("min");
    String max = localParams.get("max");
    if (min == null || "".equals(min)) {
      min = "-inf";
    }
    if (max == null || "".equals(max)) {
      max = "+inf";
    }
    log.debug("Fetching zrevrangebyscore from Redis for key: {} ({}, {})", redisKey, min, max);
    Set<Tuple> objectsWithScores = jedis.zrevrangeByScoreWithScores(redisKey, max, min);
    redisObjectsCollection = new HashSet<>();
    scores = new HashMap<>();
    for (Tuple object: objectsWithScores) {
      redisObjectsCollection.add(object.getElement());
      scores.put(object.getElement(), (float)object.getScore());
    }
  }

  private void fetchDataFromRedis(String redisMethod, String redisKey, int maxJedisRetries,
          SolrParams additionalParams) {
    int retries = 0;
    while (redisObjectsCollection == null && retries++ < maxJedisRetries + 1) {
      Jedis jedis = null;
      try {
        jedis = jedisPool.getResource();
        if (redisMethod.equalsIgnoreCase("smembers")) {
          fetchSmembers(jedis, redisKey, maxJedisRetries);
        } else if (redisMethod.equalsIgnoreCase("zrevrangebyscore") || redisMethod.equalsIgnoreCase("zrangebyscore")) {
          fetchRevrangeByScore(jedis, redisKey, maxJedisRetries, params);
        }
        jedisPool.returnResource(jedis);
      } catch (JedisException ex) {
        jedisPool.returnBrokenResource(jedis);
        log.debug("There was an error fetching data from redis. Retrying", ex);
        if (retries >= maxJedisRetries + 1) {
          throw ex;
        }
      }
    }
  }
}
