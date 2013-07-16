package hudson.plugins.scalabuilder;

import hudson.Extension;
import hudson.model.AbstractDescribableImpl;
import hudson.model.Descriptor;
import org.kohsuke.stapler.DataBoundConstructor;

/**
 *
 * @author aretter
 */
public class SourceBlock extends AbstractDescribableImpl<SourceBlock> {
    private boolean urlSource;
    private String srcUrl;
    private String srcInline;

    /**
     * @param value this parameter is named 'value' as the JSONBinding
     * expects that name. It is actually the boolean indicating if the src is
     * from a URL
     */
    @DataBoundConstructor
    public SourceBlock(final String value, final String srcUrl, final String srcInline) {
        this.urlSource = Boolean.parseBoolean(value);
        this.srcUrl = srcUrl;
        this.srcInline = srcInline;
    }

    public String getValue() {
        return Boolean.toString(urlSource);
    }

    public void setValue(final String value) {
        this.urlSource = Boolean.parseBoolean(value);
    }

    public boolean isUrlSource() {
        return urlSource;
    }

    public String getSrcUrl() {
        return srcUrl;
    }

    public void setSrcUrl(final String srcUrl) {
        this.srcUrl = srcUrl;
    }

    public String getSrcInline() {
        return srcInline;
    }

    public void setSrcInline(final String srcInline) {
        this.srcInline = srcInline;
    }
    
    @Extension
    public static class DescriptorImpl extends Descriptor<SourceBlock> {        
        @Override
        public String getDisplayName() {
            return "Scala Builder Source Block";
        }
    }
}
