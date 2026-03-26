package com.wlinsk.rd_machine.prompt;

import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class RoundPlanner {

    private final Map<Integer, RoundGoal> goals = Map.of(
            1, new RoundGoal(1, "基础理解", "请围绕文章主要人物、主题或整体场景提出第一个问题。", false),
            2, new RoundGoal(2, "细节理解", "请追问文章中的具体细节、行为或事件。", false),
            3, new RoundGoal(3, "深层理解", "请引导学生理解人物形象、作者意图或情感表达。", false),
            4, new RoundGoal(4, "引导纠偏", "请根据学生前几轮回答进行纠偏、补充引导或表达优化。", false),
            5, new RoundGoal(5, "总结巩固", "请对本次学习做简短总结，并给出鼓励性的收束反馈。", true)
    );

    public RoundGoal goalForRound(int roundNo) {
        return goals.getOrDefault(roundNo, goals.get(5));
    }
}
