package gov.cdc.prime.router.azure

import com.microsoft.azure.functions.ExecutionContext
import com.microsoft.azure.functions.annotation.FunctionName
import com.microsoft.azure.functions.annotation.TimerTrigger
import com.microsoft.azure.functions.annotation.StorageAccount

import gov.cdc.prime.router.azure.db.tables.pojos.ReportFile

import java.util.Date;

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.fasterxml.jackson.annotation.JsonProperty

import java.time.ZonedDateTime
import java.time.Duration
import java.time.format.DateTimeFormatter
import com.cronutils.model.time.ExecutionTime;
import com.cronutils.parser.CronParser;
import com.cronutils.model.definition.CronDefinitionBuilder;
import com.cronutils.model.CronType;

data class EmailSchedule ( 
    val template: String,
    val type: String,
    val cronSchedule: String,
    val organizations: List<String>? = ArrayList<String>(),
    val parameters: Map<String,String>? = HashMap<String,String>()
) {}

val sched = listOf( EmailSchedule( "daily-template", "marketing", "7 18 * * *") )

data class TimerInfo (
    
    val Schedule: Any?,
    val ScheduleStatus: TimerInfoScheduleStatus? = null,
    val IsPastDue: Boolean? = null
){}


data class TimerInfoScheduleStatus (
    val Last: String,
    val Next: String,
    val LastUpdated: String
){}
 
class EmailScheduleEngine  {

    @FunctionName("emailScheduleEngine")
    @StorageAccount("AzureWebJobsStorage")
    fun run(
        @TimerTrigger( name = "emailScheduleEngine", schedule = "*/5 * * * *") timerInfo : String,
        context: ExecutionContext
    ){

        val mapper = jacksonObjectMapper()
        val timer:TimerInfo = mapper.readValue<TimerInfo>( timerInfo );
        val schedulesToFire : List<EmailSchedule> = getSchedules().filter{ shouldFire( it ) };
        schedulesToFire.forEach {
            val schedule: EmailSchedule = it;
            val last: Date = getLastTimeFired(schedule,timerInfo);
            val orgs: List<String> = getOrgs( schedule );

            System.out.println( "processing ${schedule.template}" )
            orgs.forEach{
                val org: String = it;
                val emails: List<String> = getEmails(org);
                val reportsSinceLast: List<ReportFile> = getReportsSinceLast(org,last);

                System.out.println( "processing ${org}")
                dispatchToSendGrid( schedule.template, emails, reportsSinceLast );
            }
        }
    }

    private fun shouldFire( schedule: EmailSchedule ): Boolean{

        val parser = CronParser( CronDefinitionBuilder.instanceDefinitionFor(CronType.UNIX) );
        // Get date for last execution
        val now = ZonedDateTime.now();
        val executionTime = ExecutionTime.forCron(parser.parse(schedule.cronSchedule));
        val timeFromLastExecution = executionTime.timeFromLastExecution(now);
        if( timeFromLastExecution.get().toSeconds() <= 5*60) 
            System.out.println( "Firing ${schedule.template}")

        return ( timeFromLastExecution.get().toSeconds() <= 5*60);
    }

    /**
     * Reports the last time that the timer has been fired for this schedule
     */
    private fun getLastTimeFired( schedule: EmailSchedule, timerInfo: String ): Date {
        val parser = CronParser( CronDefinitionBuilder.instanceDefinitionFor(CronType.UNIX) );
        // Get date for last execution
        val now = ZonedDateTime.now();
        val executionTime = ExecutionTime.forCron(parser.parse(schedule.cronSchedule));

        return Date.from(executionTime.lastExecution(now).get().toInstant());
    }

    private fun getOrgs( schedule: EmailSchedule ): List<String> {
        return (
            if (schedule.organizations !== null && schedule.organizations.size > 0) 
                schedule.organizations 
            else 
                fetchAllOrgs() 
        )
    }

    /**
     * TODO: Fixme!
     */
    private fun fetchAllOrgs(): List<String>{
        return listOf( "pima-az-phd")
    }

    /**
     * TODO: Fixme!
     */
    private fun getSchedules(): List<EmailSchedule>{
        return sched;
    }

    /**
     * TODO: Fixme!
     */
    private fun getEmails( org: String ): List<String> {
        return ArrayList<String>();
    }

     /**
     * TODO: Fixme!
     */
    private fun getReportsSinceLast( org: String, last: Date ): List<ReportFile> {
        return ArrayList<ReportFile>();
    }

     /**
     * TODO: Fixme!
     */
    private fun dispatchToSendGrid( 
        template: String,
        emails: List<String>,
        reportsSinceLast: List<ReportFile>
    ){

    }



}

