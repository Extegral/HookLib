package gloomyfolken.hooklib.asm;

import gloomyfolken.hooklib.asm.IHookInjectorFactory.MethodEnter;
import gloomyfolken.hooklib.asm.IHookInjectorFactory.MethodExit;
import org.objectweb.asm.Label;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import java.util.ArrayList;
import java.util.List;

import static org.objectweb.asm.Opcodes.*;
import static org.objectweb.asm.Type.*;

/**
 * Класс, отвечающий за установку одного хука в один метод.
 * Терминология:
 * hook (хук) - вызов вашего статического метода из стороннего кода (майнкрафта, форджа, других модов)
 * targetMethod (целевой метод) - метод, куда вставляется хук
 * targetClass (целевой класс) - класс, где находится метод, куда вставляется хук
 * hookMethod (хук-метод) - ваш статический метод, который вызывается из стороннего кода
 * hookClass (класс с хуков) - класс, в котором содержится хук-метод
 * <p/>
 * Пример создания:
 * TODO
 */
public class AsmHook implements Cloneable {

    private String targetClassName;
    private String targetMethodName;
    private String targetMethodNameObfuscated;
    private List<Type> targetMethodParameters = new ArrayList<Type>(2); // анмаппится
    private Type targetMethodReturnType = Type.VOID_TYPE; // анмаппится

    private String hooksClassName;
    private String hookMethodName;
    private List<Integer> transmittableVariableIds = new ArrayList<Integer>(2);
    private List<Type> hookMethodParameters = new ArrayList<Type>(2); // анмаппится
    private Type hookMethodReturnType = Type.VOID_TYPE; // анмаппится
    private boolean hasReturnValueParameter; // если в хук-метод передается значение из return

    private ReturnCondition returnCondition = ReturnCondition.NEVER;
    private ReturnValue returnValue = ReturnValue.VOID;
    private Object primitiveConstant;

    private IHookInjectorFactory injectorFactory = ON_ENTER_FACTORY;

    public static final IHookInjectorFactory ON_ENTER_FACTORY = MethodEnter.INSTANCE;
    public static final IHookInjectorFactory ON_EXIT_FACTORY = MethodExit.INSTANCE;

    private String targetMethodDescription;
    private String hookMethodDescription;

    String getTargetClassName() {
        return targetClassName;
    }

    String getTargetMethodName(boolean obf) {
        return obf && targetMethodNameObfuscated != null ? targetMethodNameObfuscated : targetMethodName;
    }

    String getTargetMethodDescription() {
        return targetMethodDescription;
    }

    IHookInjectorFactory getInjectorFactory() {
        return injectorFactory;
    }

    private boolean hasHookMethod() {
        return hookMethodName != null && hooksClassName != null;
    }

    void inject(HookInjector inj) {
        // сохраняем значение, которое было передано return в локальную переменную
        int returnLocalId = -1;
        if (hasReturnValueParameter) {
            returnLocalId = inj.newLocal(targetMethodReturnType);
            inj.storeLocal(returnLocalId, targetMethodReturnType);
        }

        // вызываем хук-метод
        int hookResultLocalId = -1;
        if (hasHookMethod()) {
            for (int i = 0; i < hookMethodParameters.size(); i++) {
                Type parameterType = hookMethodParameters.get(i);
                int variableId = transmittableVariableIds.get(i);
                if (variableId == -1) variableId = returnLocalId;
                injectVarInsn(inj, parameterType, variableId);
            }

            inj.visitMethodInsn(INVOKESTATIC, hooksClassName.replace(".", "/"), hookMethodName, hookMethodDescription);

            if (returnValue == ReturnValue.HOOK_RETURN_VALUE || returnCondition.requiresHookMethod) {
                hookResultLocalId = inj.newLocal(hookMethodReturnType);
                inj.storeLocal(hookResultLocalId, hookMethodReturnType);
            }
        }

        // вызываем return
        if (returnCondition != ReturnCondition.NEVER) {
            Label label = inj.newLabel();


            // вставляем GOTO-переход к label'у после вызова return
            if (returnCondition != ReturnCondition.ALWAYS) {
                inj.loadLocal(hookResultLocalId, hookMethodReturnType);
                if (returnCondition == ReturnCondition.ON_TRUE) {
                    inj.visitJumpInsn(IFEQ, label);
                } else if (returnCondition == ReturnCondition.ON_NULL) {
                    inj.visitJumpInsn(IFNONNULL, label);
                } else if (returnCondition == ReturnCondition.ON_NOT_NULL) {
                    inj.visitJumpInsn(IFNULL, label);
                }
            }

            // вставляем в стак значение, которое необходимо вернуть
            if (returnValue == ReturnValue.NULL) {
                inj.visitInsn(Opcodes.ACONST_NULL);
            } else if (returnValue == ReturnValue.PRIMITIVE_CONSTANT) {
                inj.visitLdcInsn(primitiveConstant);
            } else if (returnValue == ReturnValue.HOOK_RETURN_VALUE) {
                inj.loadLocal(hookResultLocalId, hookMethodReturnType);
            }

            // вызываем return
            if (targetMethodReturnType == INT_TYPE || targetMethodReturnType == SHORT_TYPE ||
                    targetMethodReturnType == BOOLEAN_TYPE || targetMethodReturnType == BYTE_TYPE
                    || targetMethodReturnType == CHAR_TYPE) {
                inj.visitInsn(IRETURN);
            } else if (targetMethodReturnType == LONG_TYPE) {
                inj.visitInsn(LRETURN);
            } else if (targetMethodReturnType == FLOAT_TYPE) {
                inj.visitInsn(FRETURN);
            } else if (targetMethodReturnType == DOUBLE_TYPE) {
                inj.visitInsn(DRETURN);
            } else if (targetMethodReturnType == VOID_TYPE) {
                inj.visitInsn(RETURN);
            } else {
                inj.visitInsn(ARETURN);
            }

            // вставляем label, к которому идет GOTO-переход
            if (returnCondition != ReturnCondition.ALWAYS) {
                inj.visitLabel(label); // собственно label
            }
        }

        //кладем в стек значение, которое шло в return
        if (hasReturnValueParameter) {
            injectVarInsn(inj, targetMethodReturnType, returnLocalId);
        }
    }

    private void injectVarInsn(HookInjector inj, Type parameterType, int variableId) {
        int opcode;
        if (parameterType == INT_TYPE || parameterType == BYTE_TYPE || parameterType == CHAR_TYPE ||
                parameterType == BOOLEAN_TYPE || parameterType == SHORT_TYPE) {
            opcode = ILOAD;
        } else if (parameterType == LONG_TYPE) {
            opcode = LLOAD;
        } else if (parameterType == FLOAT_TYPE) {
            opcode = FLOAD;
        } else if (parameterType == DOUBLE_TYPE) {
            opcode = DLOAD;
        } else {
            opcode = ALOAD;
        }
        inj.getBasicVisitor().visitVarInsn(opcode, variableId);
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("AsmHook: ");

        sb.append(targetClassName).append('#').append(targetMethodName);
        sb.append(targetMethodDescription);
        sb.append(" -> ");
        sb.append(hooksClassName).append('#').append(hookMethodName);
        sb.append(hookMethodDescription);

        sb.append(", ReturnCondition=" + returnCondition);
        sb.append(", ReturnValue=" + returnValue);
        if (returnValue == ReturnValue.PRIMITIVE_CONSTANT) sb.append(", Constant=" + primitiveConstant);
        sb.append(", InjectorFactory: " + injectorFactory.getClass().getName());

        return sb.toString();
    }

    /**
     * По умолчанию hooksClassName принимает это значение.
     */
    public static String defaultHooksClassName;

    public static Builder newBuilder() {
        return new AsmHook().new Builder();
    }

    public class Builder extends AsmHook {

        private Builder() {
            AsmHook.this.hooksClassName = defaultHooksClassName;
        }

        /**
         * --- ОБЯЗАТЕЛЬНО ВЫЗВАТЬ ---
         * Определяет название класса, в который необходимо установить хук.
         *
         * @param className Необфусцированное название класса с указанием пакета, разделенное точками.
         *                  Обфусцированное название указывать не нужно.
         *                  Например: net.minecraft.world.World
         *                  Не используйте конструкцию вида World.class.getName(), указывайте строку самостоятельно.
         *                  Обращение World.class во время загрузки класса World вызовет ClassCircularityError.
         */
        public Builder setTargetClass(String className) {
            AsmHook.this.targetClassName = className;
            return this;
        }

        /**
         * --- ОБЯЗАТЕЛЬНО ВЫЗВАТЬ ---
         * Определяет название метода, в который необходимо вставить хук.
         * Если этот метод находится в пакете net.minecraft (и, соответственно, его название обфусцируется),
         * то нужно вызвать ещё и setTargetMethodObfuscatedName().
         * Если нужно пропатчить конструктор, то в названии метода нужно указать <init>, а обфусцированное название
         * указывать не нужно.
         *
         * @param methodName Необфусцированное название метода.
         *                   Например: getBlockId
         */
        public Builder setTargetMethod(String methodName) {
            AsmHook.this.targetMethodName = methodName;
            return this;
        }

        /**
         * --- ОБЯЗАТЕЛЬНО ВЫЗВАТЬ, ЕСЛИ НАЗВАНИЕ МЕТОДА ОБФУСЦИРУЕТСЯ (если он находится в пакете net.minecraft) ---
         * Определяет обфусцированное название метода, в который необходимо вставить хук.
         * Вызывать этот метод нужно только когда вы патчите класс Minecraft'a, а не форджа или другого мода.
         * Узнать обфусцированное название метода можно, если воспользоваться поиском по файлу mcp/temp/client_ro.srg
         * Если чистого MCP нет, то можно поискать здесь: TODO ссылка на гитхаб
         *
         * @param methodObfuscatedName Обфусцированное название метода без префикса вроде method_123456_
         *                             Например: a
         */
        public Builder setTargetMethodObfName(String methodObfuscatedName) {
            AsmHook.this.targetMethodNameObfuscated = methodObfuscatedName;
            return this;
        }

        /**
         * --- ОБЯЗАТЕЛЬНО ВЫЗВАТЬ, ЕСЛИ У ЦЕЛЕВОГО МЕТОДА ЕСТЬ ПАРАМЕТРЫ ---
         * Добавляет один или несколько параметров к списку параметров целевого метода.
         * <p/>
         * Эти параметры используются, чтобы составить описание целевого метода.
         * Чтобы однозначно определить целевой метод, недостаточно только его названия - нужно ещё и описание.
         * <p/>
         * Примеры использования:
         * import static gloomyfolken.hooklib.asm.TypeHelper.*
         * //...
         * addTargetMethodParameters(Type.INT_TYPE)
         * Type worldType = getType("net.minecraft.world.World")
         * Type playerType = getType("net.minecraft.entity.player.EntityPlayer")
         * addTargetMethodParameters(worldType, playerType, playerType)
         *
         * @param parameterTypes Типы параметров целевого метода
         * @see TypeHelper
         */
        public Builder addTargetMethodParameters(Type... parameterTypes) {
            for (Type type : parameterTypes) {
                AsmHook.this.targetMethodParameters.add(type);
            }
            return this;
        }

        /**
         * Добавляет один или несколько параметров к списку параметров целевого метода.
         * Обёртка над addTargetMethodParameters(Type... parameterTypes), которая сама строит типы из названия.
         *
         * @param parameterTypeNames Названия классов параметров целевого метода.
         *                           Например: net.minecraft.world.World
         */

        public Builder addTargetMethodParameters(String... parameterTypeNames) {
            Type[] types = new Type[parameterTypeNames.length];
            for (int i = 0; i < parameterTypeNames.length; i++) {
                types[i] = TypeHelper.getType(parameterTypeNames[i]);
            }
            return addTargetMethodParameters(types);
        }

        /**
         * --- ОБЯЗАТЕЛЬНО ВЫЗВАТЬ, ЕСЛИ ЦЕЛЕВОЙ МЕТОД ВОЗВРАЩАЕТ НЕ void ---
         * Изменяет тип, возвращаемый целевым методом.
         * По умолчанию считается, что целевой метод возвращает тип void.
         * <p/>
         * Вовращаемый тип используется, чтобы составить описание целевого метода.
         * Чтобы однозначно определить целевой метод, недостаточно только его названия - нужно ещё и описание.
         *
         * @param returnType Тип, возвращаемый целевым методом
         * @see TypeHelper
         */
        public Builder setTargetMethodReturnType(Type returnType) {
            AsmHook.this.targetMethodReturnType = returnType;
            return this;
        }

        /**
         * Изменяет тип, возвращаемый целевым методом.
         * Обёртка над setTargetMethodReturnType(Type returnType)
         *
         * @param returnType Название класса, экземпляр которого возвращает целевой метод
         */
        public Builder setTargetMethodReturnType(String returnType) {
            return setTargetMethodReturnType(TypeHelper.getType(returnType));
        }

        /**
         * --- ОБЯЗАТЕЛЬНО ВЫЗВАТЬ, ЕСЛИ НЕ ЗАДАНО defaultHooksClassName ---
         * Определяет название класса, в котором находится хук-метод.
         *
         * @param className Название класса с указанием пакета, разделенное точками.
         *                  Например: net.myname.mymod.asm.MyHooks
         */
        public Builder setHookClass(String className) {
            AsmHook.this.hooksClassName = className;
            return this;
        }

        /**
         * --- ОБЯЗАТЕЛЬНО ВЫЗВАТЬ, ЕСЛИ НУЖЕН ХУК-МЕТОД, А НЕ ПРОСТО return SOME_CONSTANT ---
         * Определяет название хук-метода.
         * ХУК-МЕТОД ДОЛЖЕН БЫТЬ СТАТИЧЕСКИМ, А ПРОВЕРКИ НА ЭТО НЕТ. Будьте внимательны.
         *
         * @param methodName Название хук-метода.
         *                   Например: myFirstHook
         */
        public Builder setHookMethod(String methodName) {
            AsmHook.this.hookMethodName = methodName;
            return this;
        }

        /**
         * --- ОБЯЗАТЕЛЬНО ВЫЗВАТЬ, ЕСЛИ У ХУК-МЕТОДА ЕСТЬ ПАРАМЕТРЫ ---
         * Добавляет параметр в список параметров хук-метода.
         * В байткоде не сохраняются названия параметров. Вместо этого приходится использовать их номера.
         * Например, в классе EntityLivingBase есть метод attackEntityFrom(DamageSource damageSource, float damage).
         * В нём будут использоваться такие номера параметров:
         * 1 - damageSource
         * 2 - damage
         * ВАЖНЫЙ МОМЕНТ: LONG И DOUBLE "ЗАНИМАЮТ" ДВА НОМЕРА.
         * Теоретически, кроме параметров в хук-метод можно передать и локальные переменные, но их
         * номера сложнее посчитать.
         * Например, в классе Entity есть метод setPosition(double x, double y, double z).
         * В нём будут такие номера параметров:
         * 1 - x
         * 2 - пропущено
         * 3 - y
         * 4 - пропущено
         * 5 - z
         * 6 - пропущено
         * <p/>
         * Код этого метода таков:
         * //...
         * float f = ...;
         * float f1 = ...;
         * //...
         * В таком случае у f будет номер 7, а у f1 - 8.
         *
         * @param parameterType Тип параметра хук-метода
         * @param variableId    ID значения, передаваемого в хук-метод
         * @throws IllegalStateException если не задано название хук-метода или класса, который его содержит
         */
        //TODO проверить, какие номера аргументов у статических методов
        public Builder addHookMethodParameter(Type parameterType, int variableId) {
            if (!AsmHook.this.hasHookMethod()) {
                throw new IllegalStateException("Hook method is not specified, so can not append " +
                        "parameter to its parameters list.");
            }
            AsmHook.this.hookMethodParameters.add(parameterType);
            AsmHook.this.transmittableVariableIds.add(variableId);
            return this;
        }

        /**
         * Добавляет параметр в список параметров целевого метода.
         * Обёртка над addHookMethodParameter(Type parameterType, int variableId)
         *
         * @param parameterTypeName Название типа параметра хук-метода.
         *                          Например: net.minecraft.world.World
         * @param variableId        ID значения, передаваемого в хук-метод
         */
        public Builder addHookMethodParameter(String parameterTypeName, int variableId) {
            return addHookMethodParameter(TypeHelper.getType(parameterTypeName), variableId);
        }

        /**
         * Добавляет в список параметров хук-метода целевой класс и передает хук-методу this.
         *
         * @throws IllegalStateException если не задан хук-метод
         */
        public Builder addThisToHookMethodParameters() {
            if (!AsmHook.this.hasHookMethod()) {
                throw new IllegalStateException("Hook method is not specified, so can not append " +
                        "parameter to its parameters list.");
            }
            AsmHook.this.hookMethodParameters.add(TypeHelper.getType(targetClassName));
            AsmHook.this.transmittableVariableIds.add(0);
            return this;
        }

        /**
         * Добавляет в список параметров хук-метода тип, возвращаемый целевым методом и
         * передает хук-методу значение, которое вернёт return.
         * Более формально, при вызове хук-метода указывает в качестве этого параметра верхнее значение в стеке.
         * На практике основное применение -
         * Например, есть такой код метода:
         * int foo = bar();
         * return foo;
         * Или такой:
         * return bar()
         * <p/>
         * В обоих случаях хук-методу можно передать возвращаемое значение перед вызовом return.
         *
         * @throws IllegalStateException если целевой метод возвращает void
         * @throws IllegalStateException если не задан хук-метод
         */
        public Builder addReturnValueToHookMethodParameters() {
            if (!AsmHook.this.hasHookMethod()) {
                throw new IllegalStateException("Hook method is not specified, so can not append " +
                        "parameter to its parameters list.");
            }
            if (AsmHook.this.targetMethodReturnType == Type.VOID_TYPE) {
                throw new IllegalStateException("Target method's return type is void, it does not make sense to " +
                        "transmit its return value to hook method.");
            }
            AsmHook.this.hookMethodParameters.add(AsmHook.this.targetMethodReturnType);
            AsmHook.this.transmittableVariableIds.add(-1);
            AsmHook.this.hasReturnValueParameter = true;
            return this;
        }

        /**
         * Задает условие, при котором после вызова хук-метода вызывается return.
         * По умолчанию return не вызывается вообще.
         * Кроме того, этот метод изменяет тип возвращаемого значения хук-метода:
         * NEVER -> void
         * ALWAYS -> void
         * ON_TRUE -> boolean
         * ON_NULL -> Object
         * ON_NOT_NULL -> Object
         *
         * @param condition Условие выхода после вызова хук-метода
         * @throws IllegalArgumentException если condition == ON_TRUE, ON_NULL или ON_NOT_NULL, но не задан хук-метод.
         * @see ReturnCondition
         */
        public Builder setReturnCondition(ReturnCondition condition) {
            if (condition.requiresHookMethod && AsmHook.this.hookMethodName == null) {
                throw new IllegalArgumentException("Hook method is not specified, so can not use return " +
                        "condition that depends on hook method.");
            }

            AsmHook.this.returnCondition = condition;
            Type returnType;
            switch (condition) {
                case NEVER:
                case ALWAYS:
                    returnType = VOID_TYPE;
                    break;
                case ON_TRUE:
                    returnType = BOOLEAN_TYPE;
                    break;
                default:
                    returnType = getType(Object.class);
                    break;
            }
            AsmHook.this.hookMethodReturnType = returnType;
            return this;
        }

        /**
         * --- ОБЯЗАТЕЛЬНО ВЫЗВАТЬ, ЕСЛИ ЦЕЛЕВОЙ МЕТОД ВОЗВРАЩАЕТ НЕ void, И ВЫЗВАН setReturnCondition ---
         * Задает значение, которое возвращается при вызове return после вызова хук-метода.
         * Следует вызывать после setReturnCondition.
         * По умолчанию возвращается void.
         * Кроме того, если value == ReturnValue.HOOK_RETURN_VALUE, то этот метод изменяет тип возвращаемого
         * значения хук-метода на тип, указанный в setTargetMethodReturnType()
         *
         * @param value возвращаемое значение
         * @throws IllegalStateException    если returnCondition == NEVER (т. е. если setReturnCondition() не вызывался).
         *                                  Нет смысла указывать возвращаемое значение, если return не вызывается.
         * @throws IllegalArgumentException если value == ReturnValue.HOOK_RETURN_VALUE, а тип возвращаемого значения
         *                                  целевого метода указан как void (или setTargetMethodReturnType ещё не вызывался).
         *                                  Нет смысла использовать значение, которое вернул хук-метод, если метод возвращает void.
         */
        public Builder setReturnValue(ReturnValue value) {
            if (AsmHook.this.returnCondition == ReturnCondition.NEVER) {
                throw new IllegalStateException("Current return condition is ReturnCondition.NEVER, so it does not" +
                        "make sense to specify the return value.");
            }
            Type returnType = AsmHook.this.targetMethodReturnType;
            if (value != ReturnValue.VOID && returnType == VOID_TYPE) {
                throw new IllegalArgumentException("Target method return value is void, so it does not make sense to " +
                        "return anything else.");
            }
            if (value == ReturnValue.VOID && returnType != VOID_TYPE) {
                throw new IllegalArgumentException("Target method return value is not void, so it is impossible " +
                        "to return VOID.");
            }
            if (value == ReturnValue.PRIMITIVE_CONSTANT && !isPrimitive(returnType)) {
                throw new IllegalArgumentException("Target method return value is not a primitive, so it is " +
                        "impossible to return PRIVITIVE_CONSTANT.");
            }
            if (value == ReturnValue.NULL && isPrimitive(returnType)) {
                throw new IllegalArgumentException("Target method return value is a primitive, so it is impossible " +
                        "to return NULL.");
            }
            if (value == ReturnValue.HOOK_RETURN_VALUE && !hasHookMethod()) {
                throw new IllegalArgumentException("Hook method is not specified, so can not use return " +
                        "value that depends on hook method.");
            }

            AsmHook.this.returnValue = value;
            if (value == ReturnValue.HOOK_RETURN_VALUE) {
                AsmHook.this.hookMethodReturnType = AsmHook.this.targetMethodReturnType;
            }
            return this;
        }

        /**
         * Возвращает тип возвращаемого значения хук-метода, если кому-то сложно "вычислить" его самостоятельно.
         *
         * @return тип возвращаемого значения хук-метода
         */
        public Type getHookMethodReturnType() {
            return hookMethodReturnType;
        }

        private boolean isPrimitive(Type type) {
            return type.getSort() > 0 && type.getSort() < 9;
        }

        /**
         * --- ОБЯЗАТЕЛЬНО ВЫЗВАТЬ, ЕСЛИ ВОЗВРАЩАЕМОЕ ЗНАЧЕНИЕ УСТАНОВЛЕНО НА PRIMITIVE_CONSTANT ---
         * Следует вызывать после setReturnValue(ReturnValue.PRIMITIVE_CONSTANT)
         * Задает константу, которая будет возвращена при вызове return.
         * Класс заданного объекта должен соответствовать примитивному типу.
         * Например, если целевой метод возвращает int, то в этот метод должен быть передан объект класса Integer.
         *
         * @param constant Объект, класс которого соответствует примитиву, который следует возвращать.
         * @throws IllegalStateException    если возвращаемое значение не установлено на PRIMITIVE_CONSTANT
         * @throws IllegalArgumentException если класс объекта constant не является обёрткой
         *                                  для примитивного типа, который возвращает целевой метод.
         */
        public Builder setPrimitiveConstant(Object constant) {
            if (AsmHook.this.returnValue != ReturnValue.PRIMITIVE_CONSTANT) {
                throw new IllegalStateException("Return value is not PRIMITIVE_CONSTANT, so it does not make sence" +
                        "to specify that constant.");
            }
            Type returnType = AsmHook.this.targetMethodReturnType;
            if (returnType == BOOLEAN_TYPE && !(constant instanceof Boolean) ||
                    returnType == CHAR_TYPE && !(constant instanceof Character) ||
                    returnType == BYTE_TYPE && !(constant instanceof Byte) ||
                    returnType == SHORT_TYPE && !(constant instanceof Short) ||
                    returnType == INT_TYPE && !(constant instanceof Integer) ||
                    returnType == LONG_TYPE && !(constant instanceof Long) ||
                    returnType == FLOAT_TYPE && !(constant instanceof Float) ||
                    returnType == DOUBLE_TYPE && !(constant instanceof Double)) {
                throw new IllegalArgumentException("Given object class does not math target method return type.");
            }

            AsmHook.this.primitiveConstant = constant;
            return this;
        }

        /**
         * Задает фабрику, которая создаст инжектор для этого хука.
         * Если говорить более человеческим языком, то этот метод определяет, где будет вставлен хук:
         * в начале метода, в конце или где-то ещё.
         * Если не создавать своих инжекторов, то можно использовать две фабрики:
         * AsmHook.ON_ENTER_FACTORY (вставляет хук на входе в метод, используется по умолчанию)
         * AsmHook.ON_EXIT_FACTORY (вставляет хук на выходе из метода)
         *
         * @param factory Фабрика, создающая инжектор для этого хука
         */
        public Builder setInjectorFactory(IHookInjectorFactory factory) {
            AsmHook.this.injectorFactory = factory;
            return this;
        }

        /**
         * Создает хук по заданным параметрам.
         *
         * @return полученный хук
         * @throws IllegalStateException если не был вызван какой-либо из обязательных методов
         */
        public AsmHook build() {
            AsmHook hook = AsmHook.this;

            hook.targetMethodDescription = Type.getMethodDescriptor(hook.targetMethodReturnType,
                    hook.targetMethodParameters.toArray(new Type[0]));

            hook.hookMethodDescription = Type.getMethodDescriptor(hook.hookMethodReturnType,
                    hook.hookMethodParameters.toArray(new Type[0]));

            try {
                hook = (AsmHook) AsmHook.this.clone();
            } catch (CloneNotSupportedException impossible) {
            }

            if (hook.targetClassName == null) {
                throw new IllegalStateException("Target class name is not specified. " +
                        "Call setTargetClassName() before build().");
            }

            if (hook.targetMethodName == null) {
                throw new IllegalStateException("Target method name is not specified. " +
                        "Call setTargetMethodName() before build().");
            }

            if (hook.targetClassName.startsWith("net.minecraft.") && hook.targetMethodNameObfuscated == null
                    && !hook.targetMethodName.equals("<init>") && !hook.targetMethodName.equals("<clinit>")) {
                throw new IllegalStateException("Obfuscated target method name is not specified. " +
                        "Call setTargetMethodObfuscatedName() before build().");
            }

            if (hook.returnValue == ReturnValue.PRIMITIVE_CONSTANT && hook.primitiveConstant == null) {
                throw new IllegalStateException("Return value is PRIMITIVE_CONSTANT, but the constant is not " +
                        " specified. Call setReturnValue() before build().");
            }

            return hook;
        }

        /**
         * Создает хук по заданым параметрам и сразу же его регистрирует.
         */
        public void buildAndRegister() {
            HookClassTransformer.registerHook(build());
        }
    }

}
