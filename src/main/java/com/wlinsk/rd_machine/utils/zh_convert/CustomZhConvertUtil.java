package com.wlinsk.rd_machine.utils.zh_convert;

import com.github.houbb.opencc4j.core.ZhConvert;
import com.github.houbb.opencc4j.core.impl.ZhConvertBootstrap;
import com.github.houbb.opencc4j.support.data.impl.DataUtil;
import com.github.houbb.opencc4j.support.datamap.IDataMap;
import com.github.houbb.opencc4j.support.datamap.impl.AbstractDataMapExtra;
import com.github.houbb.opencc4j.support.datamap.impl.DataMaps;
import com.github.houbb.opencc4j.support.segment.impl.Segments;

import java.util.List;
import java.util.Map;

/**
 * @author Trump
 * @create 2026/4/3 11:22
 */
public class CustomZhConvertUtil {

    private static final IDataMap dataMap =  new AbstractDataMapExtra(DataMaps.defaults()) {

        @Override
        protected Map<String, List<String>> tsPhraseExtra() {
//            return DataUtil.buildDataMap("/opencc/ts-phrases.txt"); // 词表暂时用不到，默认即可
            return Map.of();
        }

        @Override
        protected Map<String, List<String>> tsCharExtra() {
            return DataUtil.buildDataMap("/opencc/ts-chars.txt");
        }

        @Override
        protected Map<String, List<String>> stPhraseExtra() {
            return Map.of();
        }

        @Override
        protected Map<String, List<String>> stCharExtra() {
            return Map.of();
        }
    };

    private static final ZhConvert CONVERTER = ZhConvertBootstrap.newInstance()
            .dataMap(dataMap)
            .segment(Segments.dataMapFastForward(dataMap))
            .init();

    public static String toSimple(String var) {
        return CONVERTER.toSimple(var);
    }
}
