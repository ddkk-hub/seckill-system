package com.ddk.seckill.mapper;

import com.ddk.seckill.entity.AuthUser;
import org.apache.ibatis.annotations.*;
@Mapper
public interface AuthUserMapper {
    @Select("SELECT id,username,password_hash FROM seckill_user WHERE username=#{username}")
    AuthUser find(String username);
    @Insert("INSERT INTO seckill_user(id,username,password_hash) VALUES(#{id},#{username},#{passwordHash})")
    int insert(AuthUser user);
    @Select("SELECT EXISTS(SELECT 1 FROM seckill_order WHERE user_id=#{id}) OR EXISTS(SELECT 1 FROM seckill_async_order WHERE user_id=#{id}) OR EXISTS(SELECT 1 FROM seckill_user WHERE id=#{id})")
    boolean idUsed(long id);
}
