package com.wlinsk.rd_machine.model;

import com.alibaba.fastjson2.JSON;
import com.wlinsk.rd_machine.enums.SysCode;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

/**
 * @author Trump
 * "rspCd": "00000",
 * "rspInf": "success",
 * "rspType": 0,
 * "data":
 * "v": "1",
 * "responseTm": "1774836610000"
 */
@Data
public class Result<T> implements Serializable {
    @Serial
    private static final long serialVersionUID = 7242221971141381783L;
    private String rspCd;
    private String rspInf;
    private T data;
    private String responseTm;
    /**
     * ##响应信息类型 0，为正常返回，1为数组套字符模版
     */
    private Integer rspType = 0;
    private String v = "1";

//    private static Gson gson = new Gson();

    public static Result<Void> ok() {
        return init(SysCode.success.getCode(), SysCode.success.getMessage(), null);
    }

    public static <T> Result<T> ok(T data) {
        return init(SysCode.success.getCode(), SysCode.success.getMessage(), data);
    }

    public static Result error(String code, String msg) {
        return init(code, msg, null);
    }

    public static Result respBusiness(String code, String msg) {
        Result<String> result = new Result<>();
        result.setRspCd(SysCode.success.getCode());
        result.setRspInf(SysCode.success.getMessage());
        Map<String, String> resp = new HashMap<>();
        resp.put("rspCode", code);
        resp.put("rspInf", msg);
        result.setData(JSON.toJSONString(resp));
        result.setResponseTm(LocalDateTime.now().toString());
        return result;
    }

    public static <T> Result<T> ok(String msg, T data) {
        return init(SysCode.success.getCode(), msg, data);
    }

    public static boolean isSuccess(Result result) {
        return "00000".equals(result.getRspCd());
    }

    private static <T> Result<T> init(String code, String msg, T data) {
        Result<T> result = new Result<>();
        result.setRspCd(code);
        result.setRspInf(msg);
        result.setData(data);
        result.setResponseTm(LocalDateTime.now().toString());
        return result;
    }

    private Result() {
    }
}
