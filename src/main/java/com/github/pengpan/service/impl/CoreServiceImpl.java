package com.github.pengpan.service.impl;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.convert.Convert;
import cn.hutool.core.date.DatePattern;
import cn.hutool.core.date.DateUtil;
import cn.hutool.core.exceptions.ExceptionUtil;
import cn.hutool.core.io.resource.ResourceUtil;
import cn.hutool.core.map.MapUtil;
import cn.hutool.core.thread.ThreadUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.http.HttpUtil;
import cn.hutool.json.JSONUtil;
import cn.hutool.setting.dialect.PropsUtil;
import com.ejlchina.data.TypeRef;
import com.ejlchina.json.JSONKit;
import com.github.pengpan.client.MainClient;
import com.github.pengpan.common.cookie.CookieStore;
import com.github.pengpan.entity.*;
import com.github.pengpan.enums.BrushChannelEnum;
import com.github.pengpan.enums.DataTypeEnum;
import com.github.pengpan.service.BrushService;
import com.github.pengpan.service.CoreService;
import com.github.pengpan.util.Assert;
import com.google.common.collect.Lists;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import retrofit2.Response;

import javax.annotation.Resource;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * @author pengpan
 */
@Slf4j
@Service
public class CoreServiceImpl implements CoreService {

    public static final ThreadPoolExecutor threadPoolExecutor = new ThreadPoolExecutor(60, 60, 10,
            TimeUnit.SECONDS, new ArrayBlockingQueue<>(120), new ThreadPoolExecutor.DiscardPolicy());
    @Resource
    private MainClient mainClient;
    @Resource
    private BrushService brushService;

    @Override
    public List<Map<String, Object>> getData(DataTypeEnum dataType) {
        Assert.notNull(dataType, "[dataType]不能为空");
        String cities = ResourceUtil.readUtf8Str(dataType.getPath());
        return JSONKit.toBean(new TypeRef<List<LinkedHashMap<String, Object>>>() {
        }.getType(), cities);
    }

    @Override
    public List<Map<String, Object>> getUnit(String cityId) {
        Assert.notBlank(cityId, "[cityId]不能为空");
        return mainClient.getUnit(cityId);
    }

    @Override
    public List<Map<String, Object>> getDept(String unitId) {
        Assert.notBlank(unitId, "[unitId]不能为空");
        return mainClient.getDept(unitId).stream().flatMap(x -> {
            String child = Optional.ofNullable(x.get("childs")).map(JSONKit::toJson).orElseGet(String::new);
            return JSONKit.<List<LinkedHashMap<String, Object>>>toBean(new TypeRef<List<LinkedHashMap<String, Object>>>() {
            }.getType(), child).stream();
        }).collect(Collectors.toList());
    }

    @Override
    public List<Map<String, Object>> getDoctor(String unitId, String deptId) {
        BrushSchData data = dept(unitId, deptId, null);
        return Optional.ofNullable(data).map(BrushSchData::getDoc).orElseGet(ArrayList::new).stream()
                .map(JSONKit::toJson)
                .map(x -> JSONKit.<Map<String, Object>>toBean(new TypeRef<LinkedHashMap<String, Object>>() {
                }.getType(), x))
                .collect(Collectors.toList());
    }

    @Override
    public BrushSchData dept(String unitId, String deptId, String brushStartDate) {
        Assert.notBlank(unitId, "[unitId]不能为空");
        Assert.notBlank(deptId, "[deptId]不能为空");
        String url = "https://gate.91160.com/guahao/v1/pc/sch/dep";
        String date = StrUtil.isBlank(brushStartDate) ? DateUtil.today() : brushStartDate;
        int page = 0;
        String userKey = CookieStore.accessHash();
        BrushSch brushSch = mainClient.dept(url, unitId, deptId, date, page, userKey);

        if (brushSch == null || !Objects.equals(1, brushSch.getResult_code()) || !"200".equals(brushSch.getError_code())) {
            log.warn("获取数据失败: {}", JSONKit.toJson(brushSch));
            return null;
        }
        return brushSch.getData();
    }

    @Override
    public List<Map<String, Object>> getMember() {
        String url = "https://user.91160.com/member.html";
        String html = mainClient.htmlPage(url);
        Document document = Jsoup.parse(html);
        Element tbody = document.getElementById("mem_list");
        Assert.notNull(tbody, "就诊人为空，请先去家庭成员管理(https://user.91160.com/member.html)添加家庭成员并完成认证");
        Elements trs = tbody.getElementsByTag("tr");
        List<Map<String, Object>> memberList = new ArrayList<>();
        for (Element tr : trs) {
            String id = StrUtil.removePrefix(tr.id(), "mem");
            Elements tds = tr.getElementsByTag("td");
            Map<String, Object> member = new LinkedHashMap<>();
            member.put("id", id);
            member.put("name", tds.get(0).text());
            member.put("sex", tds.get(1).text());
            member.put("birth", tds.get(2).text());
            member.put("idCard", tds.get(3).text());
            member.put("mobile", tds.get(4).text());
            memberList.add(member);
        }
        return memberList;
    }

    @Override
    public void brushTicketTask(Config config) {
        log.info("挂号开始unitId-{}", config.getUnitId());

        printBrushChannelInfo(config.getBrushChannel());

        for (int i = 1; ; i++) {
            log.info("[{}]努力刷号中...unitId-{}", i, config.getUnitId());

            List<ScheduleInfo> schInfoList = brushService.getTicket(config);

            if (CollUtil.isEmpty(schInfoList)) {
                // 休眠
                ThreadUtil.sleep(config.getSleepTime(), TimeUnit.MILLISECONDS);
                continue;
            }

            log.info("刷到号了,unitId-{}", config.getUnitId());
            schInfoList.forEach(x -> log.info(JSONKit.toJson(x)));

            // 判断登录是否有效
            CookieStore.getLoginCookieNotNull();

            // 获取有效的参数列表
            List<Register> formList = schInfoList.stream().parallel()
                    .flatMap(x -> buildForm(x, config).stream())
                    .collect(Collectors.toList());

            // 挂号
            boolean success = doRegister(formList);
            if (success) {
                log.info("挂号成功");
                sendMailMsg(config);
                break;
            }
        }

        log.info("挂号结束");
    }

    @Async
    void sendMailMsg(Config config) {
        List<String> tos = config.getToUser();
        if (CollUtil.isEmpty(tos)) {
            return;
        }
        HashMap<String, Object> param = new HashMap<>();
        param.put("username", config.getUserName());
        param.put("doctor_name", "九价HPV");
        param.put("receiver", tos);
        HashMap<Object, Object> data = new HashMap<>();
        data.put("data", param);
        HttpUtil.post("http://47.98.176.105:90/data", JSONUtil.toJsonStr(data));
    }

    private void printBrushChannelInfo(BrushChannelEnum brushChannel) {
        if (brushChannel == null) {
            log.info("当前刷号通道: 通道1+通道2");
        } else if (brushChannel == BrushChannelEnum.CHANNEL_1) {
            log.info("当前刷号通道: 通道1");
        } else if (brushChannel == BrushChannelEnum.CHANNEL_2) {
            log.info("当前刷号通道: 通道2");
        }
    }

    private boolean doRegister(List<Register> formList) {
        boolean isMock = PropsUtil.getSystemProps().getBool("mock", false);
        if (isMock) {
            log.info("模拟挂号成功");
            return true;
        }
        if (CollUtil.isEmpty(formList)) {
            log.info("预约失败，号源无效");
            return false;
        }
        AtomicInteger failCountMax = new AtomicInteger();
        String errorMsg = StrUtil.format("同一号源预约失败次数达到{}次，已切换号源！", failCountMax);
        Map<String, Integer> failCount = MapUtil.newHashMap();

        Collections.shuffle(formList);
        List<CompletableFuture<String>> completableFutures = Lists.newArrayList();
        for (int i = 0; i < 5; i++) {
            List<CompletableFuture<String>> f = formList.stream().limit(5).map(form -> CompletableFuture.supplyAsync(() -> {
                Response<Void> submitResp = mainClient.doSubmit(
                        form.getSchData(),
                        form.getUnitId(),
                        form.getDepId(),
                        form.getDoctorId(),
                        form.getSchId(),
                        form.getMemberId(),
                        form.getAccept(),
                        form.getTimeType(),
                        form.getDetlid(),
                        form.getDetlidRealtime(),
                        form.getLevelCode(),
                        form.getAddressId(),
                        form.getAddress()
                );

                if (!submitResp.raw().isRedirect()) {
                    return "失败";
                }
                String redirectUrl = submitResp.headers().get("Location");
                String html = mainClient.htmlPage(redirectUrl);
                // 判断结果
                if (StrUtil.contains(html, "预约成功")) {
                    log.info("预约成功");
                    return "成功";
                }
                log.info("预约失败" + failCountMax.incrementAndGet() + "次");
                return "失败";
            }, threadPoolExecutor)).collect(Collectors.toList());
            completableFutures.addAll(f);
        }
        CompletableFuture<Void> allOf = CompletableFuture.allOf(completableFutures.toArray(new CompletableFuture[0]));

        CompletableFuture<List<String>> resultFuture = allOf.thenApply(v -> completableFutures.stream().map(s -> {
            try {
                return s.get();
            } catch (InterruptedException | ExecutionException e) {
                log.info(ExceptionUtil.stacktraceToString(e));
                return "error";
            }
        }).collect(Collectors.toList()));
        try {
            List<String> list = resultFuture.get();
            if (list.contains("成功")) {
                log.info("预约成功。总共预约次数：{}，执行结果：{}", failCountMax.get(), list);
                return true;
            }
            log.info("一组预约失败，准备换下一组。总共失败次数：{}，执行结果：{}", failCountMax.get(), list);
        } catch (InterruptedException | ExecutionException e) {
            log.info(ExceptionUtil.stacktraceToString(e));
            return false;
        }
        return false;
    }

    private List<Register> buildForm(ScheduleInfo schInfo, Config config) {
        String html = mainClient.orderPage(config.getUnitId(), config.getDeptId(), schInfo.getSchedule_id());
        Document document = Jsoup.parse(html);

        Elements elmLis = Optional.of(document)
                .map(x -> x.getElementById("delts"))
                .map(x -> x.getElementsByTag("li")).orElseGet(Elements::new);

        List<String> times = elmLis.stream().map(Element::text).collect(Collectors.toList());
        log.info("schedule_id: {}, times: {}", schInfo.getSchedule_id(), JSONKit.toJson(times));

        List<String> detlidList = elmLis.stream()
                .map(x -> x.attr("val"))
                .filter(StrUtil::isNotBlank)
                .collect(Collectors.toList());

        List<Appointment> appointmentList = elmLis.stream()
                .map(x -> Appointment.builder().name(x.text()).value(x.attr("val")).build())
                .filter(x -> StrUtil.isNotBlank(x.getValue()))
                .collect(Collectors.toList());

        if (CollUtil.isEmpty(appointmentList)) {
            return CollUtil.newArrayList();
        }

        String sch_data = Optional.of(document)
                .map(x -> x.getElementsByAttributeValue("name", "sch_data"))
                .map(Elements::val)
                .orElseGet(String::new);
        if (StrUtil.isBlank(sch_data)) {
            return CollUtil.newArrayList();
        }

        String detlid_realtime = Optional.of(document)
                .map(x -> x.getElementById("detlid_realtime"))
                .map(Element::val)
                .orElseGet(String::new);
        if (StrUtil.isBlank(detlid_realtime)) {
            return CollUtil.newArrayList();
        }

        String level_code = Optional.of(document)
                .map(x -> x.getElementById("level_code"))
                .map(Element::val)
                .orElseGet(String::new);
        if (StrUtil.isBlank(level_code)) {
            return CollUtil.newArrayList();
        }

        return appointmentList.stream()
                .map(x -> Register.builder()
                        .schData(sch_data)
                        .unitId(config.getUnitId())
                        .depId(config.getDeptId())
                        .doctorId(config.getDoctorId())
                        .schId(schInfo.getSchedule_id())
                        .memberId(config.getMemberId())
                        .accept("1")
                        .timeType(schInfo.getTime_type())
                        .detlName(x.getName())
                        .detlid(x.getValue())
                        .detlidRealtime(detlid_realtime)
                        .levelCode(level_code)
                        .addressId("3317")
                        .address("Civic Center")
                        .toDate(schInfo.getTo_date())
                        .build())
                .collect(Collectors.toList());
    }

    @Override
    public Date serverDate() {
        Response<Void> response = mainClient.serverTime();
        String date = response.raw().header("date");
        if (StrUtil.isEmpty(date)) {
            return new Date();
        }
        return DateUtil.parse(date, DatePattern.HTTP_DATETIME_PATTERN, Locale.US).toJdkDate();
    }
}
