package com.aifanyi.doc;

import java.util.List;

/**
 * 一份已解析的文档：暴露待翻译的文本段（segments），翻译完成后按同长同序的
 * 译文列表原位回填并重建文件字节（rebuild）。
 *
 * 契约：
 *  - segments() 只含需要送翻的非空白文本，顺序即文档出现顺序；
 *  - rebuild(translated) 的入参必须与 segments() 等长、顺序一一对应；
 *  - 结构（样式/标签/时间轴/键名/版式标记）由各实现负责保留，翻译层不感知格式。
 */
public abstract class ParsedDoc {

    /** 待翻译文本段（非空白），文档序。 */
    public abstract List<String> segments();

    /** 用等长同序的译文重建文档，返回输出文件字节。 */
    public abstract byte[] rebuild(List<String> translated) throws Exception;

    /** 输出文件扩展名（多数格式与输入相同；PDF 无法回填版式，输出 docx）。 */
    public abstract String outputExt();
}
