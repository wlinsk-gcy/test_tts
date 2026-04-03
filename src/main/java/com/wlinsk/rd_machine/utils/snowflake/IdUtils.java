package com.wlinsk.rd_machine.utils.snowflake;

/**
 * @author Trump
 * @create 2026/4/3 10:40
 */
public class IdUtils {

//    private final static SnowflakeIdGenerator idGenerator = new SnowflakeIdGenerator(RandomUtils.secure().randomInt(1,10), RandomUtils.secure().randomInt(1,10));
    private final static SnowflakeIdGenerator idGenerator = new SnowflakeIdGenerator(1, 1); // workerId和datacenterId必须保证全系统唯一。

    public static String build(String prefix) {
        String id = idGenerator.nextIdStr();
        return (prefix == null || prefix.isBlank()) ? id : prefix + id;
    }
}
