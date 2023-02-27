package com.github.pengpan.cmd;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.date.DatePattern;
import cn.hutool.core.date.DateUtil;
import cn.hutool.core.io.FileUtil;
import cn.hutool.core.thread.ThreadUtil;
import cn.hutool.core.util.CharsetUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.extra.spring.SpringUtil;
import cn.hutool.setting.dialect.Props;
import com.github.pengpan.common.constant.SystemConstant;
import com.github.pengpan.common.store.ProxyStore;
import com.github.pengpan.entity.Config;
import com.github.pengpan.service.CoreService;
import com.github.pengpan.service.LoginService;
import com.github.pengpan.service.impl.CoreServiceImpl;
import com.github.pengpan.util.Assert;
import com.github.pengpan.util.CommonUtil;
import com.google.common.collect.Lists;
import io.airlift.airline.Command;
import io.airlift.airline.Option;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.util.Date;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;

/**
 * @author pengpan
 */
@Slf4j
@Command(name = "register", description = "Register on 91160.com")
public class Register implements Runnable {

    @Option(
            name = {"-c", "--config"},
            title = "configuration file",
            required = true,
            description = "Path to properties configuration file.")
    private String configFile;

    @Option(
            name = {"-c1", "--config1"},
            title = "configuration file",
            required = false,
            description = "Path to properties configuration file.")
    private String configFile1;

    @Override
    public void run() {
        List<Config> list = Lists.newArrayList();
        Config config = getConfig(configFile);
        list.add(config);
        if (StrUtil.isNotEmpty(configFile1)) {
            list.add(getConfig(configFile1));
        }

        CoreService coreService = SpringUtil.getBean(CoreService.class);
        LoginService loginService = SpringUtil.getBean(LoginService.class);

        checkBasicConfig(config, coreService, loginService);
        checkEnableProxy(config);
        checkEnableAppoint(config, coreService);

        try {
            ExecutorService executor = Executors.newFixedThreadPool(2);

            CompletableFuture[] completableFutures = list.stream().map(row ->
                            CompletableFuture.supplyAsync(() -> {
                                coreService.brushTicketTask(row);
                                return "线程" + row.getUnitId() + "执行完成";
                            }, executor))
                    .toArray(CompletableFuture[]::new);

            CompletableFuture<Object> anyOf = CompletableFuture.anyOf(completableFutures);
            Object o = anyOf.get();
            log.info("抢票结束" + o);
            System.exit(0);
        } catch (Exception e) {
            log.error("", e);
            System.exit(-1);
        }
    }

    private void checkEnableProxy(Config config) {
        if (!config.isEnableProxy()) {
            return;
        }

        String proxyFilePath = config.getProxyFilePath();

        Assert.notBlank(proxyFilePath, "[proxyFilePath]不能为空");
        Assert.isTrue(StrUtil.endWithIgnoreCase(proxyFilePath, "txt"), "[proxyFilePath]格式不正确，请检查配置文件");
        Assert.isTrue(FileUtil.exist(proxyFilePath), "[proxyFilePath]文件不存在，请检查配置文件");

        List<String> proxyList = CollUtil.newArrayList();

        List<String> lines = FileUtil.readUtf8Lines(proxyFilePath);
        for (String line : lines) {
            if (StrUtil.isEmpty(line)) {
                continue;
            }
            Matcher matcher = SystemConstant.PROXY_PATTERN.matcher(line);
            if (!matcher.matches()) {
                continue;
            }
            proxyList.add(line);
        }

        Assert.notEmpty(proxyList, "[proxyFilePath]至少要有一个正确格式的代理项");
        Assert.notNull(config.getProxyMode(), "[proxyMode]格式不正确，请检查配置文件");

        ProxyStore.setProxyList(proxyList);
        ProxyStore.setEnabled(true);
        ProxyStore.setProxyMode(config.getProxyMode());
    }

    private void checkEnableAppoint(Config config, CoreService coreService) {
        if (!config.isEnableAppoint()) {
            return;
        }

        Assert.notBlank(config.getAppointTime(), "[appointTime]不能为空");
        Date appointTime = CommonUtil.parseDate(config.getAppointTime(), DatePattern.NORM_DATETIME_PATTERN);
        Assert.notNull(appointTime, "[appointTime]格式不正确，请检查配置文件");

        Date serverDate = coreService.serverDate();
        log.info("当前服务器时间: {}", DateUtil.formatDateTime(serverDate));
        log.info("指定的挂号时间: {}", DateUtil.formatDateTime(appointTime));

        long waitTime = appointTime.getTime() - serverDate.getTime();
        waitTime = waitTime < 0 ? 0 : waitTime;
        log.info("需等待: {}秒", TimeUnit.MILLISECONDS.toSeconds(waitTime));

        log.info("等待中...");
        ThreadUtil.sleep(waitTime);
        log.info("时间到！！！");
    }

    private Config getConfig(String configFile) {
        Assert.notBlank(configFile, "请指定配置文件");
        Assert.isTrue(configFile.endsWith(Props.EXT_NAME), "配置文件不正确");
        File file = FileUtil.file(configFile);
        Assert.isTrue(file.exists(), "配置文件不存在，请检查文件路径");
        Props props = new Props(file, CharsetUtil.CHARSET_UTF_8);
        Config config = new Config();
        props.fillBean(config, null);
        return config;
    }

    private void checkBasicConfig(Config config, CoreService coreService, LoginService loginService) {
        Assert.notBlank(config.getUserName(), "[userName]不能为空，请检查配置文件");
        Assert.notBlank(config.getPassword(), "[password]不能为空，请检查配置文件");
        Assert.isTrue(loginService.doLogin(config.getUserName(), config.getPassword()), "登录失败，请检查账号和密码");
        Assert.notBlank(config.getMemberId(), "[memberId]不能为空，请检查配置文件");
        Assert.isTrue(coreService.getMember().stream()
                .map(x -> String.valueOf(x.get("id")))
                .anyMatch(x -> StrUtil.equals(x, config.getMemberId())), "[memberId]不正确，请检查配置文件");
        Assert.notBlank(config.getCityId(), "[cityId]不能为空，请检查配置文件");
        Assert.notBlank(config.getUnitId(), "[unitId]不能为空，请检查配置文件");
        Assert.notBlank(config.getDeptId(), "[deptId]不能为空，请检查配置文件");
        Assert.notBlank(config.getDoctorId(), "[doctorId]不能为空，请检查配置文件");
        Assert.notEmpty(config.getWeeks(), "[weeks]不能为空，请检查配置文件");
        Assert.notEmpty(config.getDays(), "[days]不能为空，请检查配置文件");
        Assert.isTrue(config.getSleepTime() >= 0, "[sleepTime]格式不正确，请检查配置文件");
        if (StrUtil.isNotBlank(config.getBrushStartDate())) {
            Date brushStartDate = CommonUtil.parseDate(config.getBrushStartDate(), DatePattern.NORM_DATE_PATTERN);
            Assert.notNull(brushStartDate, "[brushStartDate]格式不正确，请检查配置文件");
            Date today = DateUtil.beginOfDay(new Date());
            Assert.isTrue(brushStartDate.getTime() >= today.getTime(), "[brushStartDate]刷号起始日期不能小于当前日期");
        }
    }
}
