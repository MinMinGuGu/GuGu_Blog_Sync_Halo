package com.gugumin.halo.service.impl;

import com.gugumin.core.pojo.Article;
import com.gugumin.core.service.IHandlerInitSite;
import com.gugumin.halo.config.HaloConfig;
import com.gugumin.halo.pojo.request.PostsQuery;
import com.gugumin.halo.pojo.request.PostsStatus;
import com.gugumin.halo.pojo.response.PostsResponse;
import com.gugumin.halo.service.IHaloApi;
import com.gugumin.halo.utils.JsonUtil;
import com.jayway.jsonpath.JsonPath;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import net.minidev.json.JSONArray;
import org.springframework.stereotype.Service;

import javax.annotation.PreDestroy;
import javax.annotation.Resource;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

/**
 * The type Halo site.
 *
 * @author minmin
 * @date 2023 /03/08
 */
@Slf4j
@Service
public class HaloInitSiteImpl implements IHandlerInitSite {
    private static final String DEFAULT_EDITOR_TYPE = "MARKDOWN";
    private static final ExecutorService EXECUTOR_SERVICE = Executors.newCachedThreadPool(r -> new Thread(r, "haloSite-task"));
    @Resource
    private IHaloApi haloApi;
    @Resource
    private HaloConfig haloConfig;


    @PreDestroy
    private void closer() {
        EXECUTOR_SERVICE.shutdown();
    }

    @Override
    public List<Article> getArticles() {
        log.info("开始获取halo站点的所有文章来进行托管");
        List<Integer> idList = getAllPublishedMdId();
        log.debug("成功获取halo站点的所有文章id");
        log.debug("getAllPublishedId={}", idList);
        return analyzeArticleList(idList);
    }

    @SneakyThrows
    private List<Article> analyzeArticleList(List<Integer> idList) {
        CountDownLatch countDownLatch = new CountDownLatch(idList.size());
        List<Article> synchronizedList = Collections.synchronizedList(new LinkedList<>());
        for (Integer id : idList) {
            EXECUTOR_SERVICE.execute(() -> {
                try {
                    String responseJson = haloApi.getPosts(id);
                    log.debug("haloApi.getPost()={}", responseJson);
                    Map<String, String> dataMap = JsonPath.read(responseJson, "$.data");
                    PostsResponse postsResponse = JsonUtil.json2Obj(JsonUtil.obj2Json(dataMap), PostsResponse.class);
                    synchronizedList.add(postsResponse.toArticle(haloConfig.getMetaType()));
                } finally {
                    countDownLatch.countDown();
                }
            });
        }
        log.info("等待解析halo站点的所有文章");
        countDownLatch.await();
        log.info("成功获取halo站点的所有文章");
        return synchronizedList;
    }

    private List<Integer> getAllPublishedMdId() {
        PostsQuery postsQuery = new PostsQuery();
        postsQuery.setSize(Integer.MAX_VALUE);
        postsQuery.setStatus(PostsStatus.PUBLISHED.getValue());
        JSONArray idJsonArray = JsonPath.read(haloApi.getPosts(postsQuery), "$.data.content");
        return idJsonArray.stream().map(content -> {
            String editorType = JsonPath.read(content, "$.editorType").toString();
            return DEFAULT_EDITOR_TYPE.equals(editorType) ? Integer.parseInt(JsonPath.read(content, "$.id").toString()) : null;
        }).filter(Objects::nonNull).collect(Collectors.toList());
    }
}
