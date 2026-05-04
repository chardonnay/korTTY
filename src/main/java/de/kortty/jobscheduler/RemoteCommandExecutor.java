package de.kortty.jobscheduler;

public interface RemoteCommandExecutor {

    JobSchedulerRemoteSession.CommandResult execute(String command) throws Exception;

    JobSchedulerRemoteSession.CommandResult execute(String command, String stdin) throws Exception;
}
