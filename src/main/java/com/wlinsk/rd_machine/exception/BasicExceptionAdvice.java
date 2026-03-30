package com.wlinsk.rd_machine.exception;

import com.wlinsk.rd_machine.enums.SysCode;
import com.wlinsk.rd_machine.model.Result;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseBody;

import java.util.Objects;

/**
 * @Author: Trump
 */
@ControllerAdvice
@Slf4j
public class BasicExceptionAdvice {
    @ExceptionHandler(value = {MethodArgumentNotValidException.class})
    @ResponseBody
    public Result handleMethodArgumentNotValidException(MethodArgumentNotValidException ex){
        log.error(ex.getMessage(),ex);
        return Result.error(SysCode.PARAMETER_ERROR.getCode(), Objects.requireNonNull(ex.getBindingResult().getFieldError()).getDefaultMessage());
    }
    /**
     * Validator Get请求校验拦截
     * @param ex BindException
     * @return Result
     */
    @ResponseBody
    @ExceptionHandler
    public Result beanPropertyBindingResult(BindException ex) {
        log.error(ex.getMessage(),ex);
        FieldError fieldError = ex.getBindingResult().getFieldError();
        if (fieldError == null) {
            Result.error(SysCode.SYSTEM_ERROE.getCode(), SysCode.SYSTEM_ERROE.getMessage());
        }
        return Result.error(SysCode.PARAMETER_ERROR.getCode(), fieldError.getDefaultMessage());
    }

    @ExceptionHandler(BasicException.class)
    @ResponseBody
    public Result exceBasicAir(BasicException e){
        log.error("-----BasicException----:",e);
        return Result.error(e.getStatus(),e.getMessage());
    }

    @ExceptionHandler(Exception.class)
    @ResponseBody
    public Result exceException(Exception e){
        log.error("-----Exception----:",e);
        return Result.error(SysCode.SYSTEM_ERROE.getCode(), SysCode.SYSTEM_ERROE.getMessage());
    }

    @ExceptionHandler(ConstraintViolationException.class)
    @ResponseBody
    public Result handlerConstraintViolationException(ConstraintViolationException e) {
        log.error(e.getMessage(), e);
        return Result.error(SysCode.PARAMETER_ERROR.getCode(), e.getMessage());
    }
}
