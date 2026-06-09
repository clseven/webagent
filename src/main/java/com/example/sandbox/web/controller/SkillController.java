package com.example.sandbox.web.controller;

import com.example.sandbox.web.model.entity.Skill;
import com.example.sandbox.web.model.response.ApiResponse;
import com.example.sandbox.web.service.SkillService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.util.List;

/**
 * 技能管理 API
 *
 * @author example
 * @date 2026/05/14
 */
@RestController
@RequestMapping("/api/skills")
public class SkillController {

    @Autowired
    private SkillService skillService;

    /**
     * 列出所有技能
     */
    @GetMapping
    public ApiResponse<List<Skill>> listSkills() {
        List<Skill> skills = skillService.listSkills();
        return ApiResponse.success(skills);
    }

    /**
     * 获取技能详情
     */
    @GetMapping("/{id}")
    public ApiResponse<Skill> getSkill(@PathVariable String id) {
        try {
            Skill skill = skillService.getSkill(id);
            return ApiResponse.success(skill);
        } catch (IOException e) {
            return ApiResponse.error(500, "Failed to read skill: " + e.getMessage());
        }
    }

    /**
     * 设置技能根目录
     */
    @PostMapping("/set-root")
    public ApiResponse<Void> setSkillRootPath(@RequestBody LoadRequest request) {
        skillService.setSkillRootPath(request.getDirectory());
        return ApiResponse.success();
    }

    /**
     * 加载请求
     */
    public static class LoadRequest {
        private String directory;

        public String getDirectory() {
            return directory;
        }

        public void setDirectory(String directory) {
            this.directory = directory;
        }
    }
}
