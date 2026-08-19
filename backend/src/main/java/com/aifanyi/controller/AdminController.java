package com.aifanyi.controller;

import com.aifanyi.common.BizException;
import com.aifanyi.common.R;
import com.aifanyi.entity.TranslationTask;
import com.aifanyi.entity.TextTranslation;
import com.aifanyi.entity.User;
import com.aifanyi.mapper.TranslationTaskMapper;
import com.aifanyi.mapper.TextTranslationMapper;
import com.aifanyi.mapper.UserMapper;
import com.aifanyi.monitor.ApiConcurrencyMonitor;
import com.aifanyi.security.SecurityUtils;
import com.aifanyi.service.AuthService;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Server-side protected operations for the local administrator console. */
@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
public class AdminController {

    private static final int MAX_LIST_SIZE = 200;

    private final UserMapper userMapper;
    private final TranslationTaskMapper taskMapper;
    private final TextTranslationMapper textTranslationMapper;
    private final com.aifanyi.mapper.FeedbackMapper feedbackMapper;
    private final ApiConcurrencyMonitor apiConcurrencyMonitor;

    public record DashboardSummary(long totalUsers, long enabledUsers, long adminUsers,
                                   long totalTasks, long processingTasks, long failedTasks,
                                   long completedTasks, long todayTasks) {
    }

    public record UserRow(Long id, String username, String nickname, String role,
                          Integer enabled, LocalDateTime createdAt, LocalDateTime updatedAt,
                          long taskCount) {
    }

    public record TaskRow(Long id, Long userId, String username, String originalFilename,
                          String mode, String mediaType, String sourceLang, String targetLang,
                          String status, Integer progress, String errorMsg, LocalDateTime createdAt,
                          LocalDateTime updatedAt) {
    }

    public record TextTranslationRow(Long id, String targetLang, String preview, String model,
                                     Long elapsedMs, Integer untranslatedLines, LocalDateTime createdAt) {
    }
    public record UpdateUserRequest(String role, Boolean enabled) {
    }

    @GetMapping("/summary")
    public R<DashboardSummary> summary() {
        requireAdmin();
        long totalUsers = countUsers(null, null);
        long enabledUsers = countUsers(null, 1);
        long adminUsers = countUsers(AuthService.ROLE_ADMIN, null);
        long totalTasks = taskMapper.selectCount(Wrappers.<TranslationTask>lambdaQuery());
        long processingTasks = taskMapper.selectCount(Wrappers.<TranslationTask>lambdaQuery()
                .notIn(TranslationTask::getStatus, List.of("DONE", "FAILED")));
        long failedTasks = taskMapper.selectCount(Wrappers.<TranslationTask>lambdaQuery()
                .eq(TranslationTask::getStatus, "FAILED"));
        long completedTasks = taskMapper.selectCount(Wrappers.<TranslationTask>lambdaQuery()
                .eq(TranslationTask::getStatus, "DONE"));
        long todayTasks = taskMapper.selectCount(Wrappers.<TranslationTask>lambdaQuery()
                .ge(TranslationTask::getCreatedAt, LocalDateTime.now().toLocalDate().atStartOfDay()));
        return R.ok(new DashboardSummary(totalUsers, enabledUsers, adminUsers, totalTasks,
                processingTasks, failedTasks, completedTasks, todayTasks));
    }

    @GetMapping("/users")
    public R<List<UserRow>> users(@RequestParam(required = false) String keyword) {
        requireAdmin();
        String key = keyword == null ? "" : keyword.trim();
        var query = Wrappers.<User>lambdaQuery().orderByDesc(User::getCreatedAt).last("LIMIT " + MAX_LIST_SIZE);
        if (!key.isEmpty()) {
            query.and(q -> q.like(User::getUsername, key).or().like(User::getNickname, key));
        }
        List<User> users = userMapper.selectList(query);
        Map<Long, Long> taskCounts = taskMapper.selectList(Wrappers.<TranslationTask>lambdaQuery()
                        .select(TranslationTask::getUserId))
                .stream()
                .collect(Collectors.groupingBy(TranslationTask::getUserId, Collectors.counting()));
        return R.ok(users.stream().map(user -> new UserRow(
                user.getId(), user.getUsername(), user.getNickname(), normalizeRole(user.getRole()),
                normalizedEnabled(user), user.getCreatedAt(), user.getUpdatedAt(),
                taskCounts.getOrDefault(user.getId(), 0L))).toList());
    }

    @PutMapping("/users/{id}")
    public R<Void> updateUser(@PathVariable Long id, @RequestBody UpdateUserRequest req) {
        User currentAdmin = requireAdmin();
        User target = userMapper.selectById(id);
        if (target == null) throw new BizException(404, "User not found");
        if (req == null || (req.role() == null && req.enabled() == null)) {
            throw new BizException(400, "Provide an account status or role to update");
        }
        if (id.equals(currentAdmin.getId()) && Boolean.FALSE.equals(req.enabled())) {
            throw new BizException(400, "You cannot disable the account currently in use");
        }
        String oldRole = normalizeRole(target.getRole());
        int oldEnabled = normalizedEnabled(target);
        String newRole = req.role() == null ? oldRole : normalizeRequestedRole(req.role());
        int newEnabled = req.enabled() == null ? oldEnabled : (req.enabled() ? 1 : 0);
        boolean removesActiveAdmin = AuthService.ROLE_ADMIN.equals(oldRole) && oldEnabled == 1
                && (!AuthService.ROLE_ADMIN.equals(newRole) || newEnabled == 0);
        if (removesActiveAdmin && countUsers(AuthService.ROLE_ADMIN, 1) <= 1) {
            throw new BizException(400, "Keep at least one enabled administrator account");
        }
        target.setRole(newRole);
        target.setEnabled(newEnabled);
        userMapper.updateById(target);
        return R.ok();
    }

    @GetMapping("/users/{id}/text-translations")
    public R<List<TextTranslationRow>> userTextTranslations(@PathVariable Long id) {
        requireAdmin();
        if (userMapper.selectById(id) == null) throw new BizException(404, "User not found");
        List<TextTranslation> rows = textTranslationMapper.selectList(Wrappers.<TextTranslation>lambdaQuery()
                .eq(TextTranslation::getUserId, id)
                .orderByDesc(TextTranslation::getCreatedAt)
                .last("LIMIT " + MAX_LIST_SIZE));
        return R.ok(rows.stream().map(row -> new TextTranslationRow(row.getId(), row.getTargetLang(),
                row.getPreview(), row.getModel(), row.getElapsedMs(), row.getUntranslatedLines(),
                row.getCreatedAt())).toList());
    }
    @GetMapping("/concurrency")
    public R<List<ApiConcurrencyMonitor.Snapshot>> concurrency() {
        requireAdmin();
        return R.ok(apiConcurrencyMonitor.snapshots());
    }

    @GetMapping("/users/{id}/tasks")
    public R<List<TaskRow>> userTasks(@PathVariable Long id) {
        requireAdmin();
        User user = userMapper.selectById(id);
        if (user == null) throw new BizException(404, "User not found");
        List<TranslationTask> tasks = taskMapper.selectList(Wrappers.<TranslationTask>lambdaQuery()
                .eq(TranslationTask::getUserId, id)
                .orderByDesc(TranslationTask::getCreatedAt)
                .last("LIMIT " + MAX_LIST_SIZE));
        return R.ok(toTaskRows(tasks, Map.of(id, user.getUsername())));
    }
    @GetMapping("/tasks")
    public R<List<TaskRow>> tasks(@RequestParam(required = false) String status,
                                  @RequestParam(required = false) String keyword) {
        requireAdmin();
        var query = Wrappers.<TranslationTask>lambdaQuery().orderByDesc(TranslationTask::getCreatedAt)
                .last("LIMIT " + MAX_LIST_SIZE);
        if (status != null && !status.isBlank() && !"ALL".equalsIgnoreCase(status)) {
            query.eq(TranslationTask::getStatus, status.trim());
        }
        if (keyword != null && !keyword.isBlank()) {
            query.like(TranslationTask::getOriginalFilename, keyword.trim());
        }
        List<TranslationTask> tasks = taskMapper.selectList(query);
        List<Long> userIds = tasks.stream().map(TranslationTask::getUserId).distinct().toList();
        Map<Long, String> usernames = userIds.isEmpty() ? Map.of() : userMapper.selectBatchIds(userIds)
                .stream().collect(Collectors.toMap(User::getId, User::getUsername));
        return R.ok(toTaskRows(tasks, usernames));
    }

    @DeleteMapping("/tasks/{id}")
    public R<Void> deleteTask(@PathVariable Long id) {
        requireAdmin();
        if (taskMapper.selectById(id) == null) throw new BizException(404, "Task not found");
        taskMapper.deleteById(id);
        return R.ok();
    }

    // ---- 帮助中心「反馈与建议」 ----

    public record FeedbackRow(Long id, Long userId, String username, String contact,
                              String content, LocalDateTime createdAt) {
    }

    @GetMapping("/feedback")
    public R<List<FeedbackRow>> feedback() {
        requireAdmin();
        List<com.aifanyi.entity.Feedback> rows = feedbackMapper.selectList(
                Wrappers.<com.aifanyi.entity.Feedback>lambdaQuery()
                        .orderByDesc(com.aifanyi.entity.Feedback::getId)
                        .last("LIMIT " + MAX_LIST_SIZE));
        List<Long> userIds = rows.stream().map(com.aifanyi.entity.Feedback::getUserId)
                .filter(java.util.Objects::nonNull).distinct().toList();
        Map<Long, String> usernames = userIds.isEmpty() ? Map.of() : userMapper.selectBatchIds(userIds)
                .stream().collect(Collectors.toMap(User::getId, User::getUsername));
        return R.ok(rows.stream().map(f -> new FeedbackRow(
                f.getId(), f.getUserId(),
                f.getUserId() == null ? "游客" : usernames.getOrDefault(f.getUserId(), "已注销用户"),
                f.getContact(), f.getContent(), f.getCreatedAt())).toList());
    }

    @DeleteMapping("/feedback/{id}")
    public R<Void> deleteFeedback(@PathVariable Long id) {
        requireAdmin();
        feedbackMapper.deleteById(id);
        return R.ok();
    }

    private static List<TaskRow> toTaskRows(List<TranslationTask> tasks, Map<Long, String> usernames) {
        return tasks.stream().map(task -> new TaskRow(
                task.getId(), task.getUserId(), usernames.getOrDefault(task.getUserId(), "Deleted user"),
                task.getOriginalFilename(), task.getMode(), task.getMediaType(), task.getSourceLang(),
                task.getTargetLang(), task.getStatus(), task.getProgress(), task.getErrorMsg(),
                task.getCreatedAt(), task.getUpdatedAt())).toList();
    }
    private User requireAdmin() {
        Long userId = SecurityUtils.currentUserId();
        User user = userId == null ? null : userMapper.selectById(userId);
        if (user == null || !AuthService.ROLE_ADMIN.equals(normalizeRole(user.getRole()))
                || normalizedEnabled(user) != 1) {
            throw new BizException(403, "Administrator permission is required");
        }
        return user;
    }

    private long countUsers(String role, Integer enabled) {
        var query = Wrappers.<User>lambdaQuery();
        if (role != null) query.eq(User::getRole, role);
        if (enabled != null) query.eq(User::getEnabled, enabled);
        Long count = userMapper.selectCount(query);
        return count == null ? 0 : count;
    }

    private static String normalizeRole(String role) {
        return AuthService.ROLE_ADMIN.equalsIgnoreCase(role) ? AuthService.ROLE_ADMIN : AuthService.ROLE_USER;
    }

    private static String normalizeRequestedRole(String role) {
        if (AuthService.ROLE_ADMIN.equalsIgnoreCase(role) || AuthService.ROLE_USER.equalsIgnoreCase(role)) {
            return normalizeRole(role);
        }
        throw new BizException(400, "Role must be ADMIN or USER");
    }

    private static int normalizedEnabled(User user) {
        return user.getEnabled() == null || user.getEnabled() == 1 ? 1 : 0;
    }
}
