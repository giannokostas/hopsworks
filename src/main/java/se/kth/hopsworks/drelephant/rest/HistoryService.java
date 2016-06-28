package se.kth.hopsworks.drelephant.rest;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.TimeUnit;
import javax.annotation.security.RolesAllowed;
import javax.ejb.EJB;
import javax.ejb.TransactionAttribute;
import javax.ejb.TransactionAttributeType;
import javax.inject.Inject;
import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.Consumes;
import javax.ws.rs.GET;
import javax.ws.rs.HeaderParam;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.GenericEntity;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.SecurityContext;
import org.json.JSONObject;
import se.kth.bbc.jobs.jobhistory.JobDetailDTO;
import se.kth.bbc.jobs.jobhistory.JobHeuristicDTO;
import se.kth.bbc.jobs.jobhistory.JobHeuristicDetailsDTO;
import se.kth.bbc.jobs.jobhistory.JobProposedConfigurationDTO;
import se.kth.bbc.jobs.jobhistory.JobsHistory;
import se.kth.bbc.jobs.jobhistory.JobsHistoryFacade;
import se.kth.bbc.jobs.jobhistory.YarnAppHeuristicResultDetailsFacade;
import se.kth.bbc.jobs.jobhistory.YarnAppHeuristicResultFacade;
import se.kth.bbc.jobs.jobhistory.YarnAppResult;
import se.kth.bbc.jobs.jobhistory.YarnAppResultFacade;
import se.kth.bbc.jobs.model.description.JobDescriptionFacade;
import se.kth.bbc.project.Project;
import se.kth.bbc.project.ProjectFacade;
import se.kth.hopsworks.controller.ResponseMessages;
import se.kth.hopsworks.filters.AllowedRoles;
import se.kth.hopsworks.rest.AppException;
import se.kth.hopsworks.rest.JobService;
import se.kth.hopsworks.rest.JsonResponse;
import se.kth.hopsworks.rest.NoCacheResponse;


@Path("history")
@RolesAllowed({"HOPS_ADMIN", "HOPS_USER"})
@TransactionAttribute(TransactionAttributeType.NEVER)
public class HistoryService {
    
  private static final String DR_ELEPHANT_ADDRESS = "http://bbc1.sics.se:18001";  
  
  private static final String MEMORY_HEURISTIC_CLASS = "com.linkedin.drelephant.spark.heuristics.MemoryLimitHeuristic";
  private static final String TOTAL_DRIVE_MEMORY = "Total driver memory allocated";
  private static final String TOTAL_EXECUTOR_MEMORY = "Total executor memory allocated";
  private static final String TOTAL_STORAGE_MEMORY = "Total memory allocated for storage";

  private static final String STAGE_RUNTIME_HEURISTIC_CLASS = "com.linkedin.drelephant.spark.heuristics.StageRuntimeHeuristic";
  private static final String AVERAGE_STATE_FAILURE = "Spark average stage failure rate";
  private static final String PROBLEMATIC_STAGES = "Spark problematic stages";
  private static final String STAGE_COMPLETED = "Spark stage completed";       
  private static final String STAGE_FAILED = "Spark stage failed"; 
   
  private static final String JOB_RUNTIME_HEURISTIC_CLASS = "com.linkedin.drelephant.spark.heuristics.JobRuntimeHeuristic";
  private static final String AVERAGE_JOB_FAILURE = "Spark average job failure rate";
  private static final String JOBS_COMPLETED = "Spark completed jobs number";
  private static final String JOBS_FAILED_NUMBER = "Spark failed jobs number";
  
  private static final String EXECUTOR_LOAD_BALANCE_CLASS = "com.linkedin.drelephant.spark.heuristics.ExecutorLoadHeuristic";
  
  private List<JobHeuristicDetailsDTO> resultsForAnalysis = new ArrayList<>();
  
  @EJB
  private NoCacheResponse noCacheResponse;
  @EJB
  private YarnAppResultFacade yarnAppResultFacade;
  @EJB
  private ProjectFacade projectFacade;
  @Inject
  private JobService jobs;
  @EJB
  private JobDescriptionFacade jobFacade;
  @EJB
  private JobsHistoryFacade jobsHistoryFacade;
  @EJB
  private YarnAppHeuristicResultFacade yarnAppHeuristicResultsFacade;  
  @EJB
  private YarnAppHeuristicResultDetailsFacade yarnAppHeuristicResultDetailsFacade;
  
  @GET
  @Path("all/{projectId}")
  @Produces(MediaType.APPLICATION_JSON)
  @AllowedRoles(roles = {AllowedRoles.ALL})
    public Response getAllProjects(@PathParam("projectId") int projectId,   
        @Context SecurityContext sc,
        @Context HttpServletRequest req) throws AppException{
        
    Project returnProject = projectFacade.find(projectId);
        
    List<YarnAppResult> appResults = yarnAppResultFacade.findByUsername(returnProject.getName() + "__meb10000");
    GenericEntity<List<YarnAppResult>> yarnApps
        = new GenericEntity<List<YarnAppResult>>(appResults) {
    };

    return noCacheResponse.getNoCacheResponseBuilder(Response.Status.OK).entity(
        yarnApps).build();
    }
    
    
  @GET
  @Path("details/jobs/{jobId}")
  @Produces(MediaType.APPLICATION_JSON)
  @AllowedRoles(roles = {AllowedRoles.ALL})
  public Response getJob(@PathParam("jobId") String jobId,
      @Context SecurityContext sc,
      @Context HttpServletRequest req,
      @HeaderParam("Access-Control-Request-Headers") String requestH) throws AppException{

        JsonResponse json = getJobDetailsFromDrElephant(jobId); 
        return noCacheResponse.getNoCacheResponseBuilder(Response.Status.OK).entity(json).build();
    }

  
  @POST
    @Path("heuristics")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @AllowedRoles(roles = {AllowedRoles.DATA_OWNER})
    public Response Heuristics(JobDetailDTO jobDetailDTO,
            @Context SecurityContext sc,
            @Context HttpServletRequest req) throws AppException {
        
        JobHeuristicDTO jobsHistoryResult = jobsHistoryFacade.searchHeuristicRusults(jobDetailDTO);
        
        Iterator<String> jobIt = jobsHistoryResult.getSimilarAppIds().iterator();
        
        while(jobIt.hasNext()){
            String appId = jobIt.next();
            JsonResponse json = getJobDetailsFromDrElephant(appId);
            JobsHistory jobsHistory = jobsHistoryFacade.findByAppId(appId);
            
            // Check if Dr.Elephant can find the Heuristic details for this application.
            // If the status is FAILED then continue to the next iteration.
            if(json.getStatus()=="FAILED"){
                continue;
            }
        
            StringBuilder jsonString = (StringBuilder) json.getData();
            JSONObject jsonObj = new JSONObject(jsonString.toString());
        
            String totalSeverity = jsonObj.get("severity").toString();
            
            int yarnAppHeuristicIdMemory = yarnAppHeuristicResultsFacade.searchByIdAndClass(appId, MEMORY_HEURISTIC_CLASS);
            int yarnAppHeuristicIdStage = yarnAppHeuristicResultsFacade.searchByIdAndClass(appId, STAGE_RUNTIME_HEURISTIC_CLASS);
            int yarnAppHeuristicIdJob = yarnAppHeuristicResultsFacade.searchByIdAndClass(appId, JOB_RUNTIME_HEURISTIC_CLASS);
            
            
            JobHeuristicDetailsDTO jhD = new JobHeuristicDetailsDTO(appId, totalSeverity);
            jhD.setTotalDriverMemory(yarnAppHeuristicResultDetailsFacade.searchByIdAndName(yarnAppHeuristicIdMemory, TOTAL_DRIVE_MEMORY));
            String totalExMemory = yarnAppHeuristicResultDetailsFacade.searchByIdAndName(yarnAppHeuristicIdMemory, TOTAL_EXECUTOR_MEMORY);
            String[] splitTotalExMemory = splitExecutorMemory(totalExMemory);
            
            jhD.setAmMemory(jobsHistory.getAmMemory());
            jhD.setAmVcores(jobsHistory.getAmVcores());
            jhD.setExecutionTime(jobsHistory.getExecutionDuration());
            
            jhD.setTotalExecutorMemory(splitTotalExMemory[0]);
            jhD.setExecutorMemory(convertGBtoMB(splitTotalExMemory[1]));
            jhD.setNumberOfExecutors(Integer.parseInt(splitTotalExMemory[2]));
            
            jhD.setMemorySeverity(yarnAppHeuristicResultsFacade.searchForSeverity(appId, MEMORY_HEURISTIC_CLASS));
            jhD.setStageRuntimeSeverity(yarnAppHeuristicResultsFacade.searchForSeverity(appId, STAGE_RUNTIME_HEURISTIC_CLASS));
            jhD.setJobRuntimeSeverity(yarnAppHeuristicResultsFacade.searchForSeverity(appId, JOB_RUNTIME_HEURISTIC_CLASS));
            jhD.setLoadBalanceSeverity(yarnAppHeuristicResultsFacade.searchForSeverity(appId, EXECUTOR_LOAD_BALANCE_CLASS));
            
            jhD.setMemoryForStorage(yarnAppHeuristicResultDetailsFacade.searchByIdAndName(yarnAppHeuristicIdMemory, TOTAL_STORAGE_MEMORY));
            
            // JOBS
            jhD.setAverageJobFailure(yarnAppHeuristicResultDetailsFacade.searchByIdAndName(yarnAppHeuristicIdJob, AVERAGE_JOB_FAILURE));
            jhD.setCompletedJobsNumber(yarnAppHeuristicResultDetailsFacade.searchByIdAndName(yarnAppHeuristicIdJob, JOBS_COMPLETED));
            jhD.setFailedJobsNumber(yarnAppHeuristicResultDetailsFacade.searchByIdAndName(yarnAppHeuristicIdJob, JOBS_FAILED_NUMBER));
            
            // STAGE
            jhD.setAverageStageFailure(yarnAppHeuristicResultDetailsFacade.searchByIdAndName(yarnAppHeuristicIdStage, AVERAGE_STATE_FAILURE));
            jhD.setCompletedStages(yarnAppHeuristicResultDetailsFacade.searchByIdAndName(yarnAppHeuristicIdStage, STAGE_COMPLETED));
            jhD.setFailedStages(yarnAppHeuristicResultDetailsFacade.searchByIdAndName(yarnAppHeuristicIdStage, STAGE_FAILED));
            jhD.setProblematicStages(yarnAppHeuristicResultDetailsFacade.searchByIdAndName(yarnAppHeuristicIdStage, PROBLEMATIC_STAGES));
            
            jobsHistoryResult.addJobHeuristicDetails(jhD);
            resultsForAnalysis.add(jhD);
            
        }
        
        defaultAnalysis(jobsHistoryResult);
        premiumAnalysis(jobsHistoryResult);
        
        GenericEntity<JobHeuristicDTO> jobsHistory = new GenericEntity<JobHeuristicDTO>(jobsHistoryResult){};
        
        return noCacheResponse.getNoCacheResponseBuilder(Response.Status.OK).entity(
        jobsHistory).build();
    }
    
    
    private JsonResponse getJobDetailsFromDrElephant(String jobId){
    
        try {
                JsonResponse json = new JsonResponse();
		URL url = new URL(DR_ELEPHANT_ADDRESS + "/rest/job?id=" + jobId );
		HttpURLConnection conn = (HttpURLConnection) url.openConnection();
		conn.setRequestMethod("GET");
		conn.setRequestProperty("Accept", "application/json");

		if (conn.getResponseCode() != 200) {
                    json.setStatus("FAILED");
                    json.setData("Failed : HTTP error code : " + conn.getResponseCode());
                    json.setSuccessMessage(ResponseMessages.JOB_DETAILS);
                    conn.disconnect();
                    return json;
		}

		BufferedReader br = new BufferedReader(new InputStreamReader(
			(conn.getInputStream())));

                String output;
                StringBuilder outputBuilder = new StringBuilder();
		while ((output = br.readLine()) != null) {
                        outputBuilder.append(output);
		}
                
                json.setData(outputBuilder);
                json.setStatus("OK");
                json.setSuccessMessage(ResponseMessages.JOB_DETAILS);
		conn.disconnect();
                return json;

        } catch (MalformedURLException e) {
        } catch (IOException e) {
        }
        
        return null;
    }
    
    /*
    This method splits the total memory to the number of executors and per executor memory
    For example in the case of 1 GB (512 MB x 2),
    the method will return an array of Strings with: 
    1. The total memory (in this case 1 GB)
    2. Per executor memory (in this case 512 MB)
    3. Number of executors (in this case 2)
    */
    private String[] splitExecutorMemory(String executorMemory){
        String[] memoryDetails = new String[3];
        String[] splitParenthesis = executorMemory.split("[\\(\\)]");
        String[] parts = splitParenthesis[1].split("x");
        
        memoryDetails[0] = splitParenthesis[0].trim();  // Total memory
        memoryDetails[1] = parts[0].trim();             // per executor memory
        memoryDetails[2] = parts[1].trim();             // number of executors
        
        return memoryDetails;
    }
    
    private int convertGBtoMB(String memory){
        int memoryInMB;
        String[] splited = memory.split("\\s+");
        
        if(splited[1].equals("GB")){
            memoryInMB = Integer.parseInt(splited[0]) * 1024;
        }
        else{
            memoryInMB = Integer.parseInt(splited[0]);
        }
        return memoryInMB;
    }
    
    private void defaultAnalysis(JobHeuristicDTO jobsHistoryResult){
        int defaultAmMemory = 512;
        int defaultAmVcores = 1;
        int defaultNumOfExecutors = 1;
        int defaultExecutorsMemory = 512;
        int defaultExecutorCores = 1;
        long executionDuration = 0;
        
        Iterator<JobHeuristicDetailsDTO> itr = resultsForAnalysis.iterator();
        
        while(itr.hasNext()) {
         JobHeuristicDetailsDTO obj = itr.next();
         
         if(obj.getTotalSeverity().equals("LOW") && obj.getAmMemory()<= defaultAmMemory && obj.getAmVcores() <= defaultAmVcores &&
            obj.getNumberOfExecutors()<= defaultNumOfExecutors && obj.getExecutorMemory()<= defaultExecutorsMemory)
         {
             defaultAmMemory = obj.getAmMemory();
             defaultAmVcores = obj.getAmVcores();
             defaultNumOfExecutors = obj.getNumberOfExecutors();
             defaultExecutorsMemory = obj.getExecutorMemory();
             executionDuration = obj.getExecutionTime();
         }
      }
        JobProposedConfigurationDTO proposal = new JobProposedConfigurationDTO("Default", defaultAmMemory, defaultAmVcores, defaultNumOfExecutors,
                                    defaultExecutorCores, defaultExecutorsMemory);
        
        if (executionDuration == 0){
            proposal.setEstimatedExecutionTime(jobsHistoryResult.getEstimatedTime());
        }
        else{
            proposal.setEstimatedExecutionTime(convertMsToTime(executionDuration));
        }
        
        jobsHistoryResult.addProposal(proposal);        
    }
    
    private void premiumAnalysis(JobHeuristicDTO jobsHistoryResult){
        int defaultAmMemory = 512;
        int defaultAmVcores = 1;
        int defaultNumOfExecutors = 1;
        int defaultExecutorsMemory = 512;
        int defaultExecutorCores = 1;
        long executionDuration = 0;
        
        Iterator<JobHeuristicDetailsDTO> itr = resultsForAnalysis.iterator();
        
        while(itr.hasNext()) {
         JobHeuristicDetailsDTO obj = itr.next();
         
         if(obj.getTotalSeverity().equals("LOW") && ((obj.getAmMemory()*obj.getAmVcores() > defaultAmMemory*defaultAmVcores)
                 || (obj.getExecutorMemory()*obj.getNumberOfExecutors()>defaultExecutorsMemory*defaultNumOfExecutors))){
             defaultAmMemory = obj.getAmMemory();
             defaultAmVcores = obj.getAmVcores();
             defaultExecutorsMemory = obj.getExecutorMemory();
             executionDuration = obj.getExecutionTime();
         }
        }
         
         int blocks = jobsHistoryResult.getInputBlocks();

         if(blocks != 0){
             defaultNumOfExecutors = blocks;
         }
         
        JobProposedConfigurationDTO proposal = new JobProposedConfigurationDTO("Premium", defaultAmMemory, defaultAmVcores, defaultNumOfExecutors,
                                    defaultExecutorCores, defaultExecutorsMemory);
        if (executionDuration == 0){
            proposal.setEstimatedExecutionTime(jobsHistoryResult.getEstimatedTime());
        }
        else{
            proposal.setEstimatedExecutionTime(convertMsToTime(executionDuration));
        }
        
        jobsHistoryResult.addProposal(proposal);
    }
    
        private String convertMsToTime(long timeMs){
        
        String hms = String.format("%02d:%02d:%02d", TimeUnit.MILLISECONDS.toHours(timeMs),
            TimeUnit.MILLISECONDS.toMinutes(timeMs) - TimeUnit.HOURS.toMinutes(TimeUnit.MILLISECONDS.toHours(timeMs)),
            TimeUnit.MILLISECONDS.toSeconds(timeMs) - TimeUnit.MINUTES.toSeconds(TimeUnit.MILLISECONDS.toMinutes(timeMs)));
    
        return hms;
    }
 
}
  
    
