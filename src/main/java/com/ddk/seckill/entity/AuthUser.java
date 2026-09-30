package com.ddk.seckill.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
public record AuthUser(long id, String username, @JsonIgnore String passwordHash) {
    @Override public String toString() { return "AuthUser[id=" + id + "]"; }
}
