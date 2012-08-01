/**
 * END USER LICENSE AGREEMENT (“EULA”)
 *
 * READ THIS AGREEMENT CAREFULLY (date: 9/13/2011):
 * http://www.akiban.com/licensing/20110913
 *
 * BY INSTALLING OR USING ALL OR ANY PORTION OF THE SOFTWARE, YOU ARE ACCEPTING
 * ALL OF THE TERMS AND CONDITIONS OF THIS AGREEMENT. YOU AGREE THAT THIS
 * AGREEMENT IS ENFORCEABLE LIKE ANY WRITTEN AGREEMENT SIGNED BY YOU.
 *
 * IF YOU HAVE PAID A LICENSE FEE FOR USE OF THE SOFTWARE AND DO NOT AGREE TO
 * THESE TERMS, YOU MAY RETURN THE SOFTWARE FOR A FULL REFUND PROVIDED YOU (A) DO
 * NOT USE THE SOFTWARE AND (B) RETURN THE SOFTWARE WITHIN THIRTY (30) DAYS OF
 * YOUR INITIAL PURCHASE.
 *
 * IF YOU WISH TO USE THE SOFTWARE AS AN EMPLOYEE, CONTRACTOR, OR AGENT OF A
 * CORPORATION, PARTNERSHIP OR SIMILAR ENTITY, THEN YOU MUST BE AUTHORIZED TO SIGN
 * FOR AND BIND THE ENTITY IN ORDER TO ACCEPT THE TERMS OF THIS AGREEMENT. THE
 * LICENSES GRANTED UNDER THIS AGREEMENT ARE EXPRESSLY CONDITIONED UPON ACCEPTANCE
 * BY SUCH AUTHORIZED PERSONNEL.
 *
 * IF YOU HAVE ENTERED INTO A SEPARATE WRITTEN LICENSE AGREEMENT WITH AKIBAN FOR
 * USE OF THE SOFTWARE, THE TERMS AND CONDITIONS OF SUCH OTHER AGREEMENT SHALL
 * PREVAIL OVER ANY CONFLICTING TERMS OR CONDITIONS IN THIS AGREEMENT.
 */

package com.akiban.server.types3.aksql.aktypes;

import com.akiban.server.error.AkibanInternalException;
import com.akiban.server.types3.Attribute;
import com.akiban.server.types3.IllegalNameException;
import com.akiban.server.types3.TClass;
import com.akiban.server.types3.TClassBase;
import com.akiban.server.types3.TClassFormatter;
import com.akiban.server.types3.TExecutionContext;
import com.akiban.server.types3.TFactory;
import com.akiban.server.types3.TInstance;
import com.akiban.server.types3.TParser;
import com.akiban.server.types3.aksql.AkBundle;
import com.akiban.server.types3.aksql.AkCategory;
import com.akiban.server.types3.pvalue.PUnderlying;
import com.akiban.server.types3.pvalue.PValueSource;
import com.akiban.server.types3.pvalue.PValueTarget;
import com.akiban.sql.types.DataTypeDescriptor;
import com.akiban.sql.types.TypeId;
import com.akiban.util.AkibanAppender;
import com.google.common.math.LongMath;

import java.util.ArrayList;
import java.util.Formatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class AkIntervalSeconds extends TClassBase {

    /**
     * An SECONDS interval. Its underlying INT_64 represents microseconds, but you should not rely on that, because
     * it may change (for instance, to nanoseconds).  Instead, use #rawValueAs to get a PValueSource's values in
     * whatever unit you want.
     */
    public static TClass SECONDS = new AkIntervalSeconds();

    private static TimeUnit SECONDS_SOURCE_UNIT = TimeUnit.MICROSECONDS;

    public static long rawValueAs(PValueSource source, TimeUnit as) {
        return rawValueAs(source.getInt64(), as);
    }

    public static long rawValueAs(long secondsIntervalRaw, TimeUnit as) {
        return as.convert(secondsIntervalRaw, SECONDS_SOURCE_UNIT);
    }

    public static TInstance tInstanceFrom(DataTypeDescriptor type) {
        TypeId typeId = type.getTypeId();
        SecondsFormat format = typeIdToFormat.get(typeId);
        if (format == null)
            throw new IllegalArgumentException("couldn't convert " + type + " to " + SECONDS);
        TInstance result = SECONDS.instance(format.ordinal());
        result.setNullable(type.isNullable());
        return result;
    }

    private enum SecondsAttrs implements Attribute {
        FORMAT
    }

    private enum SecondsFormat {
        DAY("D", TypeId.INTERVAL_DAY_ID),
        HOUR("H", TypeId.INTERVAL_HOUR_ID),
        MINUTE("M", TypeId.INTERVAL_MINUTE_ID),
        SECOND("S", TypeId.INTERVAL_SECOND_ID),
        DAY_HOUR("D H", TypeId.INTERVAL_DAY_HOUR_ID),
        DAY_MINUTE("D H:M", TypeId.INTERVAL_DAY_MINUTE_ID),
        DAY_SECOND("D H:M:S", TypeId.INTERVAL_DAY_SECOND_ID),
        HOUR_MINUTE("H:M", TypeId.INTERVAL_HOUR_MINUTE_ID),
        HOUR_SECOND("H:M:S", TypeId.INTERVAL_HOUR_SECOND_ID),
        MINUTE_SECOND("M:S", TypeId.INTERVAL_MINUTE_SECOND_ID)
        ;

        public long parseToRaw(String string) {
            boolean isNegative;
            if (string.charAt(0) == '-') {
                isNegative = true;
                string = string.substring(1);
            }
            else {
                isNegative = false;
            }
            Matcher matcher = regex.matcher(string);
            if (!matcher.matches())
                throw new AkibanInternalException("couldn't parse string as " + name() + ": " + string);
            long micros = 0;
            for (int i = 0, len = matcher.groupCount(); i < len; ++i) {
                String group = matcher.group(i+1);
                TimeUnit parsedUnit = timeUnits[i];
                long parsed;
                if (parsedUnit != null) {
                    parsed = Long.parseLong(group);
                }
                else {
                    // Fractional seconds component. Need to be careful about how many digits were given.
                    // We'll normalize to nanoseconds, then convert to what we need. This isn't the most efficient,
                    // but it means we can change the underlying scale without having to remember this code.
                    // It's just a couple multiplications and one division, anyway.
                    if (group.length() > 8)
                        group = group.substring(0, 9);
                    parsed = Long.parseLong(group);
                    // how many digits short of the full 8 are we? e.g., "123" is 5 short. Need to multiply it
                    // by shortBy*10 to get to nanos
                    for (int shortBy= (8 - group.length()); shortBy > 0; --shortBy)
                        parsed = LongMath.checkedMultiply(parsed, 10L);
                    parsedUnit = TimeUnit.NANOSECONDS;
                }
                long parsedMicros = SECONDS_SOURCE_UNIT.convert(parsed, parsedUnit);
                micros = LongMath.checkedAdd(micros, parsedMicros);
            }

            return isNegative ? -micros : micros;
        }

        private SecondsFormat(String pattern, TypeId typeId) {
            StringBuilder compiled = new StringBuilder();
            List<TimeUnit> timeUnits = new ArrayList<TimeUnit>();
            for (int i = 0, len = pattern.length(); i < len; ++i) {
                char c = pattern.charAt(i);
                switch (c) {
                case 'D':
                    compiled.append("(\\d+)");
                    timeUnits.add(TimeUnit.DAYS);
                    break;
                case 'H':
                    compiled.append("(\\d+)");
                    timeUnits.add(TimeUnit.HOURS);
                    break;
                case 'M':
                    compiled.append("(\\d+)");
                    timeUnits.add(TimeUnit.MINUTES);
                    break;
                case 'S':
                    compiled.append("(\\d+)(?:\\.(\\d+))?");
                    timeUnits.add(TimeUnit.SECONDS);
                    timeUnits.add(null);
                    break;
                case ' ':
                case ':':
                    compiled.append(c);
                    break;
                default:
                    throw new IllegalArgumentException("illegal pattern: " + pattern);
                }
            }

            this.regex = Pattern.compile(compiled.toString());
            this.timeUnits = timeUnits.toArray(new TimeUnit[timeUnits.size()]);
            this.typeId = typeId;
        }

        private final Pattern regex;
        private final TimeUnit[] timeUnits;
        private final TypeId typeId;
    }

    @Override
    public void attributeToString(int attributeIndex, long value, StringBuilder output) {
        if (attributeIndex == SecondsAttrs.FORMAT.ordinal() )
            attributeToString(SecondsFormat.values(), value, output);
        else
            super.attributeToString(attributeIndex, value,  output);
    }

    @Override
    public DataTypeDescriptor dataTypeDescriptor(TInstance instance) {
        Boolean isNullable = instance.nullability(); // on separate line to make NPE easier to catch
        TypeId typeId = formatFor(instance).typeId;
        return new DataTypeDescriptor(typeId, isNullable);
    }

    @Override
    public void putSafety(TExecutionContext context, TInstance sourceInstance, PValueSource sourceValue,
                          TInstance targetInstance, PValueTarget targetValue) {
        throw new UnsupportedOperationException();
    }

    @Override
    public TInstance instance() {
        return instance(SecondsFormat.SECOND.ordinal());
    }

    @Override
    protected TInstance doPickInstance(TInstance instance0, TInstance instance1) {
        return instance();
    }

    @Override
    protected void validate(TInstance instance) {
        int formatId = instance.attribute(SecondsAttrs.FORMAT);
        if ( (formatId < 0) || (formatId >= SecondsFormat.values().length) )
            throw new IllegalNameException("unrecognized format ID: " + formatId);
    }

    @Override
    public TFactory factory() {
        throw new UnsupportedOperationException();
    }

    private AkIntervalSeconds() {
        super(
                AkBundle.INSTANCE.id(),
                "interval seconds",
                AkCategory.DATE_TIME,
                SecondsAttrs.class,
                formatter,
                1,
                1,
                8,
                PUnderlying.INT_64,
                parser);
    }

    private static TClassFormatter formatter = new TClassFormatter() {
        @Override
        public void format(TInstance instance, PValueSource source, AkibanAppender out) {
            long micros = rawValueAs(source, TimeUnit.MICROSECONDS);

            long days = rawValueAs(micros, TimeUnit.DAYS);
            micros -= TimeUnit.DAYS.toMicros(days);

            long hours = rawValueAs(micros, TimeUnit.HOURS);
            micros -= TimeUnit.HOURS.toMicros(hours);

            long minutes = rawValueAs(micros, TimeUnit.MINUTES);
            micros -= TimeUnit.MINUTES.toMicros(minutes);

            long seconds = rawValueAs(micros, TimeUnit.SECONDS);
            micros -= TimeUnit.SECONDS.toMicros(seconds);

            Formatter formatter = new Formatter(out.getAppendable());
            formatter.format("INTERVAL '%d %d:%d:%d.%05d", days, hours, minutes, seconds, micros);
        }
    };

    private static TParser parser = new TParser() {
        @Override
        public void parse(TExecutionContext context, PValueSource in, PValueTarget out) {
            TInstance instance = context.outputTInstance();
            SecondsFormat format = formatFor(instance);
            String inString = in.getString();
            long raw = format.parseToRaw(inString);
            out.putInt64(raw);
        }
    };

    private static SecondsFormat formatFor(TInstance instance) {
        int format = instance.attribute(SecondsAttrs.FORMAT);
        return SecondsFormat.values()[format];
    }

    private static final Map<TypeId,SecondsFormat> typeIdToFormat = createTypeIdToFormatMap();

    private static Map<TypeId, SecondsFormat> createTypeIdToFormatMap() {
        SecondsFormat[] values = SecondsFormat.values();
        Map<TypeId, SecondsFormat> map = new HashMap<TypeId, SecondsFormat>(values.length);
        for (SecondsFormat format : values)
            map.put(format.typeId, format);
        return map;
    }
}
