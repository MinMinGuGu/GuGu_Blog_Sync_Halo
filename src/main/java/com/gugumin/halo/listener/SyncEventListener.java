package com.gugumin.halo.listener;

import com.gugumin.core.event.AddArticleEvent;
import com.gugumin.core.event.DeleteArticleEvent;
import com.gugumin.core.event.UpdateArticleEvent;
import com.gugumin.core.pojo.Article;
import com.gugumin.core.pojo.Meta;
import com.gugumin.halo.pojo.request.*;
import com.gugumin.halo.service.IHaloApi;
import com.jayway.jsonpath.JsonPath;
import lombok.SneakyThrows;
import net.minidev.json.JSONArray;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;

import javax.annotation.Resource;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

/**
 * The type Sync event listener.
 *
 * @author minmin
 * @date 2023 /03/26
 */
@Component
public class SyncEventListener {
    private static final Object CATEGORIES_MONITOR = new Object();
    private static final Object TAG_MONITOR = new Object();
    private static final ExecutorService EXECUTOR_SERVICE = Executors.newCachedThreadPool(r -> new Thread(r, "sync-task"));
    private static final String DEFAULT_EDITOR_TYPE = "MARKDOWN";
    @Resource
    private IHaloApi haloApi;

    /**
     * Add article.
     *
     * @param addArticleEvent the add article event
     */
    @SneakyThrows
    @EventListener(AddArticleEvent.class)
    public void addArticle(AddArticleEvent addArticleEvent) {
        List<Article> articleList = addArticleEvent.getArticleList();
        CountDownLatch countDownLatch = new CountDownLatch(articleList.size());
        for (Article article : articleList) {
            EXECUTOR_SERVICE.execute(() -> {
                try {
                    createOrUpdatePosts(article, true);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    countDownLatch.countDown();
                }
            });
        }
        countDownLatch.await();
    }

    /**
     * Update article.
     *
     * @param updateArticleEvent the update article event
     */
    @SneakyThrows
    @EventListener(UpdateArticleEvent.class)
    public void updateArticle(UpdateArticleEvent updateArticleEvent) {
        List<Article> articleList = updateArticleEvent.getArticleList();
        CountDownLatch countDownLatch = new CountDownLatch(articleList.size());
        for (Article article : articleList) {
            EXECUTOR_SERVICE.execute(() -> {
                try {
                    createOrUpdatePosts(article, false);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    countDownLatch.countDown();
                }
            });
        }
        countDownLatch.await();
    }

    /**
     * Delete article.
     *
     * @param deleteArticleEvent the delete article event
     */
    @SneakyThrows
    @EventListener(DeleteArticleEvent.class)
    public void deleteArticle(DeleteArticleEvent deleteArticleEvent) {
        List<Article> articleList = deleteArticleEvent.getArticleList();
        List<String> titleList = articleList.stream().map(Article::getName).collect(Collectors.toList());
        CountDownLatch countDownLatch = new CountDownLatch(titleList.size());
        List<Integer> postIdList = Collections.synchronizedList(new LinkedList<>());
        for (String title : titleList) {
            EXECUTOR_SERVICE.execute(() -> {
                try {
                    PostsQuery postsQuery = new PostsQuery();
                    postsQuery.setKeyword(title);
                    String responseJson = haloApi.getPosts(postsQuery);
                    Integer id = JsonPath.read(responseJson, "$.data.content[0].id");
                    postIdList.add(id);
                } finally {
                    countDownLatch.countDown();
                }
            });
        }
        countDownLatch.await();
        haloApi.delPosts(postIdList);
    }

    private void createOrUpdatePosts(Article article, boolean isCreate) {
        List<String> categoriesNameList = article.getMeta().getCategories().stream().map(Meta.Category::getName).collect(Collectors.toList());
        List<Integer> categoriesIdList = createCategoriesList(categoriesNameList);
        List<String> tagNameList = article.getMeta().getTags().stream().map(Meta.Tag::getName).collect(Collectors.toList());
        List<Integer> tagIdList = createTagList(tagNameList);
        PostsRequest postsRequest = createPostRequest(article, categoriesIdList, tagIdList);
        if (isCreate) {
            haloApi.postPosts(postsRequest);
        } else {
            PostsQuery postsQuery = new PostsQuery();
            postsQuery.setKeyword(article.getName());
            String getPostsJson = haloApi.getPosts(postsQuery);
            Integer postsId = JsonPath.read(getPostsJson, "$.data.content[0].id");
            haloApi.putPosts(postsId, postsRequest);
        }
    }

    private List<Integer> createCategoriesList(List<String> categoriesNameList) {
        if (CollectionUtils.isEmpty(categoriesNameList)) {
            return Collections.emptyList();
        }
        synchronized (CATEGORIES_MONITOR) {
            return getIdListFromCategoryOrTag(categoriesNameList, true);
        }
    }

    private synchronized List<Integer> createTagList(List<String> tagNameList) {
        if (CollectionUtils.isEmpty(tagNameList)) {
            return Collections.emptyList();
        }
        synchronized (TAG_MONITOR) {
            return getIdListFromCategoryOrTag(tagNameList, false);
        }
    }

    private PostsRequest createPostRequest(Article article, List<Integer> categoriesIdList, List<Integer> tagIdList) {
        PostsRequest postsRequest = new PostsRequest();
        postsRequest.setCategoryIds(categoriesIdList);
        postsRequest.setTagIds(tagIdList);
        String context = article.getContext();
        postsRequest.setOriginalContent(context);
        postsRequest.setTitle(article.getName());
        postsRequest.setEditorType(DEFAULT_EDITOR_TYPE);
        postsRequest.setStatus(PostsStatus.PUBLISHED.getValue());
        postsRequest.setSummary(article.getMeta().getSummary());
        postsRequest.setKeepRaw(false);
        return postsRequest;
    }

    private List<Integer> getIdListFromCategoryOrTag(List<String> nameList, boolean isCategory) {
        String getResponseJson = isCategory ? haloApi.getCategories() : haloApi.getTags();
        JSONArray categoriesNameJsonArray = JsonPath.read(getResponseJson, "$..name");
        List<String> haloNameList = categoriesNameJsonArray.stream().map(Object::toString).collect(Collectors.toList());
        List<String> needCreateList = nameList.stream().filter(categoriesName -> !haloNameList.contains(categoriesName)).collect(Collectors.toList());
        List<Integer> idList = new LinkedList<>();
        if (CollectionUtils.isEmpty(needCreateList)) {
            JSONArray dataJsonArray = JsonPath.read(getResponseJson, "$.data");
            for (Object jsonObject : dataJsonArray) {
                if (jsonObject instanceof Map<?, ?>) {
                    Map<?, ?> categoriesMap = (Map<?, ?>) jsonObject;
                    if (nameList.contains(categoriesMap.get("name").toString())) {
                        idList.add((Integer) categoriesMap.get("id"));
                    }
                }
            }
            return idList;
        }
        for (String needCreate : needCreateList) {
            String postResponseJson;
            if (isCategory) {
                CategoryRequest categoryRequest = new CategoryRequest();
                categoryRequest.setName(needCreate);
                postResponseJson = haloApi.postCategory(categoryRequest);
            } else {
                TagsRequest tagsRequest = new TagsRequest();
                tagsRequest.setName(needCreate);
                postResponseJson = haloApi.postTags(tagsRequest);
            }
            Integer categoriesId = JsonPath.read(postResponseJson, "$.data.id");
            idList.add(categoriesId);
        }
        return idList;
    }
}
