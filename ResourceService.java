package com.xii.resource;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import org.elasticsearch.action.search.MultiSearchRequest;
import org.elasticsearch.action.search.MultiSearchResponse;
import org.elasticsearch.action.search.SearchRequest;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.index.query.QueryBuilder;
import org.elasticsearch.index.query.QueryBuilders;
import org.elasticsearch.search.aggregations.AggregationBuilder;
import org.elasticsearch.search.aggregations.AggregationBuilders;
import org.elasticsearch.search.aggregations.Aggregations;
import org.elasticsearch.search.aggregations.bucket.histogram.DateHistogramAggregationBuilder;
import org.elasticsearch.search.aggregations.bucket.histogram.DateHistogramInterval;
import org.elasticsearch.search.aggregations.bucket.histogram.Histogram;
import org.elasticsearch.search.aggregations.bucket.terms.Terms;
import org.elasticsearch.search.aggregations.bucket.terms.Terms.Bucket;
import org.elasticsearch.search.aggregations.bucket.terms.TermsAggregationBuilder;
import org.elasticsearch.search.aggregations.metrics.Max;
import org.elasticsearch.search.builder.SearchSourceBuilder;
import org.elasticsearch.search.sort.FieldSortBuilder;
import org.elasticsearch.search.sort.SortOrder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.xii.models.ResourceVO;
import com.xii.models.ServerVO;
import com.xii.server.ServerService;

@Service
public class ResourceService {

	static final Logger logger = LoggerFactory.getLogger(ResourceService.class);

	@Autowired
	private ResourceDao resourceDao;
	@Autowired
	private ServerService serverService;

	public Map<String, Object> getResource(ResourceVO resourceVO) {
		Map<String, Object> resultMap = new HashMap<String, Object>();

		String start = resourceVO.getStartTime();
		String end = resourceVO.getEndTime();
		String serverIds = resourceVO.getServer_id();

		resultMap.put("result", getResourceMetricByTimeRange(start, end, serverIds));
		
		return resultMap;
	}

	public List<ResourceVO> getResourceMetricByTimeRange(String start, String end, String serverIds) {
		List<ResourceVO> resultList = new ArrayList<ResourceVO>();
		List<String> serverList = Arrays.asList(serverIds.split(","));

		ZonedDateTime startZdt = ZonedDateTime.parse(start,
				DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.of("Asia/Seoul")));
		ZonedDateTime endZdt = ZonedDateTime.parse(end,
				DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.of("Asia/Seoul")));
		Instant startTime = startZdt.toInstant();
		Instant endTime = endZdt.toInstant();

		DateHistogramInterval fixedInterval = calcFixedInterval(startTime, endTime);
		
		// fixedInterval -> 지정한 구간에 따라 변하도록
		Duration range = Duration.between(startTime, endTime);
		if (range.compareTo(Duration.ofMinutes(1)) < 0) {
			Long rangeSec = range.getSeconds();
			fixedInterval = DateHistogramInterval.seconds(rangeSec.intValue() -1);
		}
		
		// 1. timestamp 구간 지정 
		DateHistogramAggregationBuilder byDate = AggregationBuilders.dateHistogram("byDate").field("@timestamp")
				.fixedInterval(fixedInterval);

		MultiSearchRequest multiRequest = new MultiSearchRequest();

		// 1-1. search request : system resource metric
		// host 별로 terms aggregation -> 각각 sub aggregation : metrics
		TermsAggregationBuilder byHost = AggregationBuilders.terms("byHost").field("host.name");
		byHost.subAggregation(AggregationBuilders.max("disk_read_bytes").field("system.diskio.read.bytes"));
		byHost.subAggregation(AggregationBuilders.max("disk_write_bytes").field("system.diskio.write.bytes"));
		byHost.subAggregation(AggregationBuilders.max("network_received_bytes").field("system.network.in.bytes"));
		byHost.subAggregation(AggregationBuilders.max("network_sent_bytes").field("system.network.out.bytes"));
		byHost.subAggregation(AggregationBuilders.max("cpu_used").field("system.cpu.user.pct"));
		byHost.subAggregation(AggregationBuilders.max("mem_used").field("system.memory.actual.used.pct"));

		// host terms aggregation을 datehistogram aggregation의 하위 aggs로 등록
		// 전체 구조 : timestamp 구간 -> host -> metrics
		byDate.subAggregation(byHost);

		// 1-1-1. search query 정의 
		QueryBuilder query = QueryBuilders.boolQuery()
				.must(QueryBuilders.rangeQuery("@timestamp").gte(startTime).lt(endTime));

		SearchSourceBuilder builder = new SearchSourceBuilder();
		SearchRequest sysRequest = new SearchRequest("metricbeat-*");

		builder.aggregation(byDate);
		builder.query(query);
		builder.sort(new FieldSortBuilder("@timestamp").order(SortOrder.ASC));

		sysRequest.source(builder);
		multiRequest.add(sysRequest);

		// 1-2. search request : gpu resource metric
		// host 별로 terms aggregation -> 각각 sub aggregation : metrics
		SearchRequest gpuRequest = new SearchRequest("nvidiagpubeat-*");
		builder = new SearchSourceBuilder();
		byDate = AggregationBuilders.dateHistogram("byDate").field("@timestamp").fixedInterval(fixedInterval);
		byHost = AggregationBuilders.terms("byHost").field("host.name.keyword");
		TermsAggregationBuilder byGpu = AggregationBuilders.terms("byGpu").field("gpuIndex");
		byGpu.subAggregation(AggregationBuilders.max("gpu_used").field("utilization.gpu"))
				.subAggregation(AggregationBuilders.max("gpu_mem_used").field("memory.used"))
				.subAggregation(AggregationBuilders.max("gpu_mem_total").field("memory.total"))
				.subAggregation(AggregationBuilders.max("temperature").field("temperature.gpu"));

		byHost.subAggregation(byGpu);
		byDate.subAggregation(byHost);

		builder.query(query);
		builder.aggregation(byDate);
		builder.sort(new FieldSortBuilder("@timestamp").order(SortOrder.ASC));

		gpuRequest.source(builder);
		multiRequest.add(gpuRequest);

		// 2. search response

		MultiSearchResponse multiResponse = null;
		SearchResponse sysResponse = null;
		SearchResponse gpuResponse = null;

		try {
			multiResponse = resourceDao.getResourceMetric(multiRequest);
			sysResponse = multiResponse.getResponses()[0].getResponse();
			gpuResponse = multiResponse.getResponses()[1].getResponse();

		} catch (Exception e) {
			logger.error("Cannot get metric : {}", e.getMessage());
			return resultList;
		}

		// 3. system metric 결과 파싱
		if (sysResponse != null) {

			// 3-1. get date histogram aggs
			Aggregations dateAggs = sysResponse.getAggregations();
			Histogram dateHistogram = dateAggs.get("byDate");

			// 3-1-1. get date histogram aggs buckets
			for (org.elasticsearch.search.aggregations.bucket.histogram.Histogram.Bucket dateBucket : dateHistogram
					.getBuckets()) {
				Instant timestamp = Instant.parse(dateBucket.getKeyAsString());
				ZonedDateTime seoul = timestamp.atZone(ZoneId.of("Asia/Seoul"));
				// 3-1-2. timestamp 포맷 변환 
				String datetime = seoul.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));

				// 3-2. get host terms aggs
				Aggregations hostAggs = dateBucket.getAggregations();
				Terms hostTerms = hostAggs.get("byHost");

				// 3-2-1. get date histogram aggs buckets
				for (Bucket hostBucket : hostTerms.getBuckets()) {
					String hostName = hostBucket.getKeyAsString();

					// 3-2-2. hostname으로 서버 ID를 구하고 
					ServerVO tempServer = new ServerVO();
					tempServer.setServer_host(hostName);
					String serverId = Integer.toString(serverService.getServer(tempServer).getId());

					// 3-3. get metrics aggs 
					Aggregations resourceAggs = hostBucket.getAggregations();

					// 3-3-1. 반복문을 사용해서 조작하기 위해 list에 추가 
					List<Max> resourceResultList = new ArrayList<Max>();
					resourceResultList.add(resourceAggs.get("disk_read_bytes"));
					resourceResultList.add(resourceAggs.get("disk_write_bytes"));
					resourceResultList.add(resourceAggs.get("network_received_bytes"));
					resourceResultList.add(resourceAggs.get("network_sent_bytes"));
					resourceResultList.add(resourceAggs.get("cpu_used"));
					resourceResultList.add(resourceAggs.get("mem_used"));


					// 3-3-2. name, value, time -> 최종 결과 list에 추가 
					for (Max resource : resourceResultList) {
						if (resource.getValueAsString().equals("-Infinity")) {
							continue;
						}

						ResourceVO resourceVO = new ResourceVO();
						resourceVO.setMetric(resource.getName());
						resourceVO.setTime(datetime);
						resourceVO.setServer_name(hostName);

						if (resource.getName().equals("cpu_used") || resource.getName().equals("mem_used")) {
							double value = resource.getValue() * 100;
							resourceVO.setValue(Double.toString(value));
						} else {
							resourceVO.setValue(resource.getValueAsString());
						}

						resourceVO.setServer_id(serverId);
						resultList.add(resourceVO);
					}

				}
			}
		}

		// 4. gpu metric 결과 파싱 
		if (gpuResponse != null) {
			System.out.println(gpuResponse.getHits().getTotalHits());

			// 4-1. get date histogram aggs
			Aggregations dateAggs = gpuResponse.getAggregations();
			Histogram dateHistogram = dateAggs.get("byDate");

			// 4-1-1. get date histogram aggs buckets
			for (org.elasticsearch.search.aggregations.bucket.histogram.Histogram.Bucket dateBucket : dateHistogram
					.getBuckets()) {
				Instant timestamp = Instant.parse(dateBucket.getKeyAsString());
				ZonedDateTime seoul = timestamp.atZone(ZoneId.of("Asia/Seoul"));
				String datetime = seoul.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));

				// 4-2. get host terms aggs
				Aggregations hostAggs = dateBucket.getAggregations();
				Terms hostTerms = hostAggs.get("byHost");

				// 4-2-1. get date histogram aggs buckets
				for (Bucket hostBucket : hostTerms.getBuckets()) {
					String hostName = hostBucket.getKeyAsString();

					ServerVO tempServer = new ServerVO();
					tempServer.setServer_host(hostName);
					String serverId = Integer.toString(serverService.getServer(tempServer).getId());


					// 4-3. get gpu terms aggs
					Aggregations gpuAggs = hostBucket.getAggregations();
					Terms gpuTerms = gpuAggs.get("byGpu");

					for (Bucket gpuBucket : gpuTerms.getBuckets()) {
						String gpuIndex = gpuBucket.getKeyAsString();
						Aggregations gpuResAggs = gpuBucket.getAggregations();

						List<Max> gpuResourceList = new ArrayList<Max>();
						gpuResourceList.add(gpuResAggs.get("gpu_used"));
						gpuResourceList.add(gpuResAggs.get("gpu_mem_used"));

						Max memTotal = gpuResAggs.get("gpu_mem_total");
						Double memTotalValue = memTotal.getValue();

						for (Max gpuResource : gpuResourceList) {
							ResourceVO resourceVO = new ResourceVO();
							resourceVO.setTime(datetime);
							resourceVO.setServer_name(hostName);

							String metric = String.format("GPU%s|%s", gpuBucket.getKeyAsString(),
									gpuResource.getName());
							resourceVO.setMetric(metric);

							resourceVO.setServer_id(serverId);

							if (gpuResource.getName().equals("gpu_mem_used")) {
								Double memUsedPct = (gpuResource.getValue() / memTotalValue) * 100;
								resourceVO.setValue(String.format("%.2f", memUsedPct));
							} else {
								resourceVO.setValue(gpuResource.getValueAsString());
							}

							resultList.add(resourceVO);
						}
					}

				}
			}
		}

		return resultList;

	}

	// timestamp 구간 별 적당한 fixedInterval을 계산하는 함수 
	public DateHistogramInterval calcFixedInterval(Instant startTime, Instant endTime) {

		Duration range = Duration.between(startTime, endTime);

		Map<Duration, DateHistogramInterval> intervalMap = new LinkedHashMap<Duration, DateHistogramInterval>();

		intervalMap.put(Duration.ofMinutes(5), DateHistogramInterval.seconds(1));
		intervalMap.put(Duration.ofMinutes(10), DateHistogramInterval.seconds(5));
		intervalMap.put(Duration.ofMinutes(30), DateHistogramInterval.seconds(10));
		intervalMap.put(Duration.ofHours(1), DateHistogramInterval.seconds(30));
		intervalMap.put(Duration.ofHours(6), DateHistogramInterval.minutes(1));
		intervalMap.put(Duration.ofHours(12), DateHistogramInterval.minutes(5));
		intervalMap.put(Duration.ofDays(1), DateHistogramInterval.minutes(10));
		intervalMap.put(Duration.ofDays(7), DateHistogramInterval.hours(1));
		intervalMap.put(Duration.ofDays(14), DateHistogramInterval.hours(3));
		intervalMap.put(Duration.ofDays(30), DateHistogramInterval.hours(6));
		intervalMap.put(Duration.ofDays(90), DateHistogramInterval.days(1));
		intervalMap.put(Duration.ofDays(180), DateHistogramInterval.days(3));
		intervalMap.put(Duration.ofDays(365), DateHistogramInterval.days(7));

		DateHistogramInterval interval = null;

		for (Entry<Duration, DateHistogramInterval> entry : intervalMap.entrySet()) {

			if (range.compareTo(entry.getKey()) > 0) {
				continue;
			} else {
				interval = entry.getValue();
				break;
			}
		}
		// 구간이 1년 이상이면 14일 interval return 
		return interval != null ? interval : DateHistogramInterval.days(14);
	}
}
