package com.aifanyi.controller.dto;

public class TtsDtos {

    /** 音色试听请求。 */
    public record PreviewReq(String voice, Double speed) {
    }

    /** 发起配音请求。 */
    public record DubReq(String voice, Double speed, Boolean keepOriginal) {
    }
}
