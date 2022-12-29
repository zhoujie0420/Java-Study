package com.hmdp.service.impl;
import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Blog;
import com.hmdp.entity.User;
import com.hmdp.mapper.BlogMapper;
import com.hmdp.service.IBlogService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IUserService;
import com.hmdp.utils.SystemConstants;
import com.hmdp.utils.UserHolder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import javax.annotation.Resource;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static com.hmdp.utils.RedisConstants.BLOG_LIKED_KEY;

/**
 * <p>
 *  服务实现类
 * </p>
 */
@Service
public class BlogServiceImpl extends ServiceImpl<BlogMapper, Blog> implements IBlogService {

    @Resource
    private IUserService userService;

    @Resource
    private StringRedisTemplate stringRedisTemplate;


    @Override
    public Result queryHotBlog(Integer current) {
        //query by user
        Page<Blog> page = query().
                        orderByDesc("liked").
                        page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));

        //get information of page
        List<Blog> records = page.getRecords();
        // query user
        records.forEach(blog -> {
            this.queryBlogUser(blog);
            this.isBlogLiked(blog);
        });
        return Result.ok(records);
    }


    @Override
    public Result queryBlogById(Long id) {
        // query blog
        Blog blog = getById(id);
        if(blog == null){
            return Result.fail("笔记不存在");
        }
        // query user related to blog
        queryBlogUser(blog);
        // query whether isLiked
        isBlogLiked(blog);
        return Result.ok(blog);

    }


    private void isBlogLiked(Blog blog) {
        // get user
        UserDTO user = UserHolder.getUser();
        if(user == null){
            // no login
            return ;
        }
        Long userId = user.getId();
        // judge whether isLiked
        String key  = "blog:liked:" + blog.getId();
        Double score = stringRedisTemplate.opsForZSet().score(key, userId.toString());
        blog.setIsLike(score != null);

    }


    @Override
    public Result likeBlog(Long id) {
        // get user
        Long userId = UserHolder.getUser().getId();
        // judge whether isLiked
        String key = BLOG_LIKED_KEY + id;
        Double score = stringRedisTemplate.opsForZSet().score(key, userId.toString());
        if (score == null) {
            // no liked
            // database
            boolean isSuccess = update().setSql("liked = liked + 1").eq("id", id).update();
            // redis // zadd key value score
            if (isSuccess) {
                stringRedisTemplate.opsForZSet().add(key, userId.toString(), System.currentTimeMillis());
            }
        } else {
            // liked
            // database
            boolean isSuccess = update().setSql("liked = liked - 1").eq("id", id).update();
            //redis
            if(isSuccess){
                stringRedisTemplate.opsForZSet().remove(key, userId.toString());
            }
        }

        return Result.ok();
    }


    private void queryBlogUser(Blog blog) {
        Long userId = blog.getUserId();
        User user = userService.getById(userId);
        blog.setName(user.getNickName());
        blog.setIcon(user.getIcon());
    }

    @Override
    public Result queryBlogLikes(Long id) {
        String key = BLOG_LIKED_KEY + id;
        // query top 5 user isLiked
        Set<String> top5 = stringRedisTemplate.opsForZSet().range(key, 0, 4);
        if(top5 == null || top5.isEmpty()){
            return Result.ok(Collections.emptyList());
        }
        // analysis userId about it
        List<Long> ids = top5.stream().map(Long::valueOf).collect(Collectors.toList());
        String idStr = StrUtil.join(",", ids);
        //query by userId  // where id in (5,1) order by field (id ,5, 1)
        List<UserDTO> userDTOS = userService.query()
                        .in("id", ids).last("ORDER BY FIELD(id," + idStr + ")").list()
                        .stream()
        	            .map(user -> BeanUtil.copyProperties(user, UserDTO.class))
        	            .collect(Collectors.toList());
        return Result.ok(userDTOS);
    }
}
