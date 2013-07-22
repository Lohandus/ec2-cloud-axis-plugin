package hudson.plugins.ec2;

import hudson.Extension;
import hudson.matrix.MatrixBuild.MatrixBuildExecution;
import hudson.model.BuildListener;
import hudson.model.Result;
import hudson.model.Cause;
import hudson.model.Computer;
import hudson.model.Executor;
import hudson.model.Job;
import hudson.model.Label;
import hudson.model.Node;
import hudson.model.Queue;
import hudson.model.Queue.Item;
import hudson.model.Queue.Task;
import hudson.model.labels.LabelAtom;
import hudson.slaves.Cloud;
import hudson.slaves.NodeProvisioner.PlannedNode;

import java.io.IOException;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

import javax.servlet.ServletException;

import jenkins.model.Jenkins;

import org.apache.commons.lang.StringUtils;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.StaplerRequest;
import org.kohsuke.stapler.StaplerResponse;

public class EC2AxisCloud extends AmazonEC2Cloud {

	private static final String SLAVE_NUM_SEPARATOR = "__";
	private static final Map<String, JobAllocationStatus> jobsByRequestedLabels = new HashMap<String, JobAllocationStatus>();

	@DataBoundConstructor
	public EC2AxisCloud(String accessId, String secretKey, String region, String privateKey, String instanceCapStr, List<SlaveTemplate> templates) {
		super(accessId,secretKey,region, privateKey,instanceCapStr,replaceByEC2AxisSlaveTemplates(templates));
	}
	
	public boolean acceptsLabel(Label label) {
		return getTemplateGivenLabel(label) != null;
	}
	
	@Override
	public SlaveTemplate getTemplate(Label label) {
		String displayName = label.getDisplayName();
		if (displayName == null)
			return null;
		
    	String labelPrefix = StringUtils.substringBefore(displayName,SLAVE_NUM_SEPARATOR);
		LabelAtom prefixAtom = new LabelAtom(labelPrefix);
    	Ec2AxisSlaveTemplate template = (Ec2AxisSlaveTemplate)super.getTemplate(prefixAtom);
    	template.setInstanceLabel(displayName);
		return template;
	}

	public static EC2AxisCloud getCloudToUse(String ec2label) {
		Iterator<Cloud> iterator = Jenkins.getInstance().clouds.iterator();
		EC2AxisCloud cloudToUse = null;
		while(iterator.hasNext()) {
			Cloud next = iterator.next();
			if (next instanceof EC2AxisCloud) {
				if (((EC2AxisCloud)next).acceptsLabel(new LabelAtom(ec2label)))
					cloudToUse = (EC2AxisCloud) next;
			}
			
		}
		return cloudToUse;
	}

		
	@Override
	public Collection<PlannedNode> provision(Label label, int excessWorkload) {
		
		JobAllocationStatus jobStatus = jobsByRequestedLabels.get(label.getDisplayName());
		jobStatus.setAllocated();
	}
	

	public BuildListener getListenerFor(final Job<?,?> project) {
		return new BuildListenerImplementation(project);
	}

	public List<String> allocateSlavesLabels(MatrixBuildExecution context, String ec2Label, Integer numberOfSlaves) {
		removePreviousLabelsAllocatedToGivenProject(context.getProject());
		addListenerToCleanupAllocationTableOnBuildCompletion(context);
		
		Set<Label> labels = Jenkins.getInstance().getLabels();
		LinkedList<String> allocatedLabels = new LinkedList<String>();
		int lastAllocatedSlaveNumber = 0;
		
		LinkedList<String> idleLabels = new LinkedList<String>();
		for (Label label : labels) {
			String labelString = label.getDisplayName();
			if (!labelString.startsWith(ec2Label))
				continue;
			
			final String[] prefixAndSlaveNumber = labelString.split(SLAVE_NUM_SEPARATOR);
			if (prefixAndSlaveNumber.length == 1)
				continue;
			
			int slaveNumber = Integer.parseInt(prefixAndSlaveNumber[1]);
			if (slaveNumber > lastAllocatedSlaveNumber)
				lastAllocatedSlaveNumber = slaveNumber;
			
			if (hasAvailableNode(label))
				idleLabels.add(labelString);
			
			if (idleLabels.size() >= numberOfSlaves)
				break;
		}
		lastAllocatedSlaveNumber++;
		
		allocatedLabels.addAll(idleLabels);
		Integer slavesToComplete = numberOfSlaves - allocatedLabels.size();
		for (int i = 0; i < slavesToComplete; i++) {
			int slaveNumber = lastAllocatedSlaveNumber+i;
			allocatedLabels.add(ec2Label + SLAVE_NUM_SEPARATOR + slaveNumber);
		}
		
		for (String allocatedLabel : allocatedLabels) {
			jobsByRequestedLabels.put(allocatedLabel, new JobAllocationStatus(context.getProject()));
		}
		
		return allocatedLabels;
	}

	private void addListenerToCleanupAllocationTableOnBuildCompletion( MatrixBuildExecution context) {
		try {
			context.post(getListenerFor(context.getProject()));
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
	}

	private static List<SlaveTemplate> replaceByEC2AxisSlaveTemplates(List<SlaveTemplate> templates) {
		List<SlaveTemplate> ec2axisTemplates = new LinkedList<SlaveTemplate>();
		for (SlaveTemplate slaveTemplate : templates) {
			ec2axisTemplates.add(new Ec2AxisSlaveTemplate(slaveTemplate));
		}
		return ec2axisTemplates;
	}

	private boolean isLabelMissingAllocation(String templateDisplayName) {
		return !jobsByRequestedLabels.containsKey(templateDisplayName);
	}

	private void cancelAllItemsTiedToGivenLabel(String templateDisplayName) {
		Queue queue = Jenkins.getInstance().getQueue();
		Item[] items = queue.getItems();
		for (Item item : items) {
			Task task = item.task;
			if (task.getAssignedLabel().getName().equals(templateDisplayName)) {
				queue.cancel(item);
			}
		}
	}

	private Ec2AxisSlaveTemplate getTemplateGivenLabel(Label label) {
		String displayName = label.getDisplayName();
		if (displayName == null)
			return null;
		
    	String labelPrefix = StringUtils.substringBefore(displayName,SLAVE_NUM_SEPARATOR);
		LabelAtom prefixAtom = new LabelAtom(labelPrefix);
    	Ec2AxisSlaveTemplate template = (Ec2AxisSlaveTemplate)super.getTemplate(prefixAtom);
		return template;
	}
	

	private void removePreviousLabelsAllocatedToGivenProject(Job<?, ?> requestingProject) {
		Iterator<Entry<String, JobAllocationStatus>> iterator = jobsByRequestedLabels.entrySet().iterator();
		while(iterator.hasNext()) {
			Entry<String, JobAllocationStatus> entry = iterator.next();
			if (entry.getValue().job.equals(requestingProject))
				iterator.remove();
		}
	}

	private boolean hasAvailableNode(Label label) {
		Set<Node> nodes = label.getNodes();
		return  isLabelAvailable(nodes);
	}

	private boolean isLabelAvailable(Set<Node> nodes) {
		if (nodes.size() == 0)
			return true;
		
		for (Node node : nodes) {
			Computer c = node.toComputer();
			if (c.isOffline() && !c.isConnecting()) {
				return true;
			}
			if (isNodeOnlineAndAvailable(c))
				return true;
			
			if (hasAvailableExecutor(c))
				return true;
		}
		return false;
	}

	private boolean hasAvailableExecutor(Computer c) {
		final List<Executor> executors = c.getExecutors();
		for (Executor executor : executors) {
			if (executor.isIdle()) {
				return true;
			}
		}
		return false;
	}

	private boolean isNodeOnlineAndAvailable(Computer c) {
		return (c.isOnline() || c.isConnecting()) && c.isAcceptingTasks();
	}

	@Extension
	public static class DescriptorImpl extends AmazonEC2Cloud.DescriptorImpl {
	    @Override
		public String getDisplayName() {
	        return "EC2 Axis Amazon Cloud";
	    }
	}

	@SuppressWarnings("serial")
	private final class BuildListenerImplementation extends BuildStartEndListener {
		private final Job<?, ?> project;
		private BuildListenerImplementation(Job<?, ?> project) {
			this.project = project;
		}
	
		public void finished(Result result) {
			removePreviousLabelsAllocatedToGivenProject(project);
		}
	
		public void started(List<Cause> causes) {  }
	}

}
