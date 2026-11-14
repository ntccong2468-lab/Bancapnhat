import org.junit.platform.launcher.core.*;
import org.junit.platform.launcher.listeners.*;
import org.junit.platform.engine.discovery.DiscoverySelectors;
public class UiAuditCaptureRunner { public static void main(String[] args) {
  var request=LauncherDiscoveryRequestBuilder.request().selectors(DiscoverySelectors.selectClass("com.vncode.app.ui.workspace.NativeGlassEvidenceTest")).build();
  var listener=new SummaryGeneratingListener();var launcher=LauncherFactory.create();launcher.registerTestExecutionListeners(listener);launcher.execute(request);
  listener.getSummary().printTo(new java.io.PrintWriter(System.out));listener.getSummary().printFailuresTo(new java.io.PrintWriter(System.out));
  System.exit(listener.getSummary().getTestsFailedCount()==0?0:1);
}}
