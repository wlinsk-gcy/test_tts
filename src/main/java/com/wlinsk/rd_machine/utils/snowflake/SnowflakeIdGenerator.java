package com.wlinsk.rd_machine.utils.snowflake;

import java.time.Instant;

/**
 * Snowflake ID 生成器
 * <p>
 * 结构（共 64 位，最高位符号位固定为 0，不使用）：
 * <p>
 * 0 | 41 bits timestamp | 5 bits datacenterId | 5 bits workerId | 12 bits sequence
 * <p>
 * 说明：
 * 1. 41 位时间戳：单位毫秒，可用很多年
 * 2. 5 位机房 ID：0 ~ 31
 * 3. 5 位机器 ID：0 ~ 31
 * 4. 12 位序列号：同一毫秒内最多生成 4096 个 ID
 * <p>
 * 注意：
 * 1. 本类单实例线程安全
 * 2. 不同节点必须保证 datacenterId + workerId 组合唯一
 * 3. 遇到小幅时钟回拨时会短暂等待；大幅回拨直接报错
 */

/**
 * @author Trump
 * @create 2026/4/3 10:33
 */
public class SnowflakeIdGenerator {

    /**
     * 自定义起始时间戳（毫秒）
     * 建议固定一个过去的时间点，且上线后不要随意修改
     * 这里使用 2026-01-01 00:00:00 对应的毫秒值
     */
    private static final long EPOCH = 1767196800000L;

    /**
     * 各部分占用位数
     */
    private static final long WORKER_ID_BITS = 5L;
    private static final long DATACENTER_ID_BITS = 5L;
    private static final long SEQUENCE_BITS = 12L;

    /**
     * 最大值计算
     */
    private static final long MAX_WORKER_ID = ~(-1L << WORKER_ID_BITS);         // 31
    private static final long MAX_DATACENTER_ID = ~(-1L << DATACENTER_ID_BITS); // 31
    private static final long SEQUENCE_MASK = ~(-1L << SEQUENCE_BITS);           // 4095

    /**
     * 左移位数
     */
    private static final long WORKER_ID_SHIFT = SEQUENCE_BITS;                                     // 12
    private static final long DATACENTER_ID_SHIFT = SEQUENCE_BITS + WORKER_ID_BITS;               // 17
    private static final long TIMESTAMP_LEFT_SHIFT = SEQUENCE_BITS + WORKER_ID_BITS + DATACENTER_ID_BITS; // 22

    /**
     * 允许等待处理的小回拨阈值（毫秒）
     * 例如 NTP 微小校时、虚拟化环境下的小抖动
     * 超过这个值直接报错，避免生成重复 ID
     */
    private static final long MAX_BACKWARD_MS = 5L;

    private final long workerId;
    private final long datacenterId;

    /**
     * 同一毫秒内的序列号
     */
    private long sequence = 0L;

    /**
     * 上次生成 ID 的时间戳
     */
    private long lastTimestamp = -1L;

    public SnowflakeIdGenerator(long workerId, long datacenterId) {
        if (workerId < 0 || workerId > MAX_WORKER_ID) {
            throw new IllegalArgumentException(
                    String.format("workerId out of range: %d, allowed range: 0 ~ %d", workerId, MAX_WORKER_ID)
            );
        }
        if (datacenterId < 0 || datacenterId > MAX_DATACENTER_ID) {
            throw new IllegalArgumentException(
                    String.format("datacenterId out of range: %d, allowed range: 0 ~ %d", datacenterId, MAX_DATACENTER_ID)
            );
        }
        this.workerId = workerId;
        this.datacenterId = datacenterId;
    }

    /**
     * 生成下一个 ID
     */
    public synchronized long nextId() {
        long currentTimestamp = currentTimeMillis();

        if (currentTimestamp < lastTimestamp) {
            long offset = lastTimestamp - currentTimestamp;

            // 小回拨：等待时间追平
            if (offset <= MAX_BACKWARD_MS) {
                currentTimestamp = waitUntilNextMillis(lastTimestamp);
            } else {
                // 大回拨：直接失败，避免重复 ID
                throw new IllegalStateException(
                        String.format(
                                "Clock moved backwards. Refusing to generate id for %d ms. lastTimestamp=%d, currentTimestamp=%d",
                                offset, lastTimestamp, currentTimestamp
                        )
                );
            }
        }

        if (currentTimestamp == lastTimestamp) {
            // 同一毫秒内，序列自增
            sequence = (sequence + 1) & SEQUENCE_MASK;

            // 当前毫秒序列溢出，等待下一毫秒
            if (sequence == 0L) {
                currentTimestamp = waitUntilNextMillis(lastTimestamp);
            }
        } else {
            // 跨毫秒，序列重置
            sequence = 0L;
        }

        lastTimestamp = currentTimestamp;

        return ((currentTimestamp - EPOCH) << TIMESTAMP_LEFT_SHIFT)
                | (datacenterId << DATACENTER_ID_SHIFT)
                | (workerId << WORKER_ID_SHIFT)
                | sequence;
    }

    /**
     * 生成字符串形式 ID
     */
    public String nextIdStr() {
        return Long.toString(nextId());
    }

    /**
     * 阻塞到下一毫秒
     */
    private long waitUntilNextMillis(long lastTimestamp) {
        long timestamp = currentTimeMillis();
        while (timestamp <= lastTimestamp) {
            timestamp = currentTimeMillis();
        }
        return timestamp;
    }

    /**
     * 获取当前毫秒时间
     */
    protected long currentTimeMillis() {
        return System.currentTimeMillis();
    }

    /**
     * 可选：反解时间戳，便于排查问题
     */
    public static long extractTimestamp(long id) {
        return (id >>> TIMESTAMP_LEFT_SHIFT) + EPOCH;
    }

    /**
     * 可选：反解机房 ID
     */
    public static long extractDatacenterId(long id) {
        return (id >>> DATACENTER_ID_SHIFT) & MAX_DATACENTER_ID;
    }

    /**
     * 可选：反解机器 ID
     */
    public static long extractWorkerId(long id) {
        return (id >>> WORKER_ID_SHIFT) & MAX_WORKER_ID;
    }

    /**
     * 可选：反解序列号
     */
    public static long extractSequence(long id) {
        return id & SEQUENCE_MASK;
    }

    public static void main(String[] args) {
        SnowflakeIdGenerator generator = new SnowflakeIdGenerator(1, 1);

        for (int i = 0; i < 10; i++) {
            long id = generator.nextId();
            System.out.println("id = " + id
                    + ", timestamp = " + Instant.ofEpochMilli(extractTimestamp(id))
                    + ", datacenterId = " + extractDatacenterId(id)
                    + ", workerId = " + extractWorkerId(id)
                    + ", sequence = " + extractSequence(id));
        }
    }
}
