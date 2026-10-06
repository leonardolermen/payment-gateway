package com.gateway.app.api.admin.job;

import com.gateway.app.api.admin.job.dto.GiveUpRequest;
import com.gateway.app.api.admin.job.dto.JobResponse;
import com.gateway.payments.jobs.JobAdministration;
import com.gateway.payments.jobs.JobQuery;
import com.gateway.payments.jobs.JobType;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The operator's view of the job queue. No cursor: see {@link JobQuery}. {@code AdminKeyFilter}
 * guards {@code /v1/admin/**}.
 */
@RestController
@RequestMapping("/v1/admin/jobs")
public class JobsAdminController {
  private final JobAdministration administration;

  public JobsAdminController(JobAdministration administration) {
    this.administration = administration;
  }

  @GetMapping
  public List<JobResponse> list(
      @RequestParam(required = false) String status,
      @RequestParam(required = false) JobType type,
      @RequestParam(defaultValue = "" + JobQuery.DEFAULT_LIMIT) int limit) {
    JobQuery query = new JobQuery(status, type, limit);

    return administration.list(query).stream().map(JobResponse::from).toList();
  }

  @PostMapping("/{id}/run-now")
  public JobResponse runNow(@PathVariable String id) {
    return JobResponse.from(administration.runNow(id));
  }

  @PostMapping("/{id}/give-up")
  public JobResponse giveUp(@PathVariable String id, @RequestBody GiveUpRequest request) {
    return JobResponse.from(administration.giveUp(id, request.note()));
  }
}
